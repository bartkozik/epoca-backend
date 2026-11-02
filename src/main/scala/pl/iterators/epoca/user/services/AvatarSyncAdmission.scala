package pl.iterators.epoca.user.services

import pl.iterators.epoca.user.domain.UserDomain.UserId

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** No waiting queue. At capacity, new keys are dropped instead of evicting another user's live cooldown. */
private[epoca] final class AvatarSyncAdmission(
  maxActive: Int,
  maxUsers: Int,
  cooldown: FiniteDuration) {
  private var active   = 0
  private val attempts = mutable.HashMap.empty[UserId, Long]
  def acquire(userId: UserId, now: Long): Boolean = synchronized {
    val _ = attempts.filterInPlace { case (_, at) => now - at < cooldown.toNanos }
    if (active >= maxActive || attempts.contains(userId) || attempts.size >= maxUsers) false
    else {
      val _ = attempts.put(userId, now)
      active += 1
      true
    }
  }
  def release(): Unit = synchronized(active -= 1)
}
