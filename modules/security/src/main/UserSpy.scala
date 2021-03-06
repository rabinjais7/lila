package lila.security

import org.joda.time.DateTime
import reactivemongo.api.ReadPreference

import lila.common.{ EmailAddress, IpAddress }
import lila.db.dsl._
import lila.user.{ User, UserRepo }

case class UserSpy(
    ips: List[UserSpy.IPData],
    uas: List[Store.Dated[String]],
    prints: List[Store.Dated[FingerHash]],
    usersSharingIp: Set[User],
    usersSharingFingerprint: Set[User]
) {

  import UserSpy.OtherUser

  def rawIps = ips map (_.ip.value)

  def ipsByLocations: List[(Location, List[UserSpy.IPData])] =
    ips.sortBy(_.ip).groupBy(_.location).toList.sortBy(_._1.comparable)

  lazy val otherUsers: Set[OtherUser] = {
    usersSharingIp.map { u =>
      OtherUser(u, true, usersSharingFingerprint contains u)
    } ++ usersSharingFingerprint.filterNot(usersSharingIp.contains).map {
      OtherUser(_, false, true)
    }
  }

  def otherUserIds = otherUsers.map(_.user.id)
}

final class UserSpyApi(
    firewall: Firewall,
    store: Store,
    userRepo: UserRepo,
    geoIP: GeoIP,
    ip2proxy: Ip2Proxy
)(implicit ec: scala.concurrent.ExecutionContext) {

  import UserSpy._

  def apply(user: User): Fu[UserSpy] =
    store.chronoInfoByUser(user) flatMap { infos =>
      val ips    = distinctRecent(infos.map(_.datedIp))
      val prints = distinctRecent(infos.flatMap(_.datedFp))
      exploreSimilar("ip")(user) zip
        exploreSimilar("fp")(user) zip
        ip2proxy.keepProxies(ips.map(_.value)) map {
        case sharingIp ~ sharingFingerprint ~ proxies =>
          UserSpy(
            ips = ips map { ip =>
              IPData(ip, firewall blocksIp ip.value, geoIP orUnknown ip.value, proxies(ip.value))
            },
            uas = distinctRecent(infos.map(_.datedUa)),
            prints = prints,
            usersSharingIp = sharingIp,
            usersSharingFingerprint = sharingFingerprint
          )
      }
    }

  private[security] def userHasPrint(u: User): Fu[Boolean] =
    store.coll.secondaryPreferred.exists(
      $doc("user" -> u.id, "fp" $exists true)
    )

  private def exploreSimilar(field: String)(user: User): Fu[Set[User]] =
    nextValues(field)(user.id) flatMap { nValues =>
      nextUsers(field)(nValues, user)
    }

  private def nextValues(field: String)(userId: User.ID): Fu[Set[String]] =
    store.coll
      .find(
        $doc("user" -> userId),
        $doc(field -> true).some
      )
      .list[Bdoc](500, ReadPreference.secondaryPreferred)
      .map {
        _.view.flatMap(_ string field).to(Set)
      }

  private def nextUsers(field: String)(values: Set[String], user: User): Fu[Set[User]] =
    values.nonEmpty ?? {
      store.coll.secondaryPreferred.distinctEasy[String, Set](
        "user",
        $doc(
          field $in values,
          "user" $ne user.id
        )
      ) flatMap { userIds =>
        userIds.nonEmpty ?? (userRepo byIds userIds) map (_.toSet)
      }
    }

  def getUserIdsWithSameIpAndPrint(userId: User.ID): Fu[Set[User.ID]] =
    for {
      ips <- nextValues("ip")(userId)
      fps <- nextValues("fp")(userId)
      users <- (ips.nonEmpty && fps.nonEmpty) ?? store.coll.secondaryPreferred.distinctEasy[User.ID, Set](
        "user",
        $doc(
          "ip" $in ips,
          "fp" $in fps,
          "user" $ne userId
        )
      )
    } yield users
}

object UserSpy {

  import Store.Dated

  case class OtherUser(user: User, byIp: Boolean, byFingerprint: Boolean)

  // distinct values, keeping the most recent of duplicated values
  // assumes all is sorted by most recent first
  def distinctRecent[V](all: List[Dated[V]]): List[Dated[V]] =
    all
      .foldLeft(Map.empty[V, DateTime]) {
        case (acc, Dated(v, _)) if acc.contains(v) => acc
        case (acc, Dated(v, date))                 => acc + (v -> date)
      }
      .view
      .map { case (v, date) => Dated(v, date) }
      .to(List)

  case class IPData(ip: Dated[IpAddress], blocked: Boolean, location: Location, proxy: Boolean)

  case class WithMeSortedWithEmails(others: List[OtherUser], emails: Map[User.ID, EmailAddress]) {
    def emailValueOf(u: User) = emails.get(u.id).map(_.value)
  }

  def withMeSortedWithEmails(
      userRepo: UserRepo,
      me: User,
      others: Set[OtherUser]
  )(implicit ec: scala.concurrent.ExecutionContext): Fu[WithMeSortedWithEmails] = {
    val othersList = others.toList
    userRepo.emailMap(me.id :: othersList.map(_.user.id)) map { emailMap =>
      WithMeSortedWithEmails(
        (OtherUser(me, true, true) :: othersList).sortBy(-_.user.createdAt.getMillis),
        emailMap
      )
    }
  }
}
