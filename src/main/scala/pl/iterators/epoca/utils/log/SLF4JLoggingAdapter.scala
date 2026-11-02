package pl.iterators.epoca.utils.log

import org.apache.pekko.event.LoggingAdapter
import org.slf4j.Logger

class SLF4JLoggingAdapter(logger: Logger) extends LoggingAdapter {
  override protected def notifyError(message: String): Unit = logger.error(message)

  override protected def notifyError(cause: Throwable, message: String): Unit = logger.error(message, cause)

  override protected def notifyDebug(message: String): Unit = logger.debug(message)

  override def isInfoEnabled: Boolean = logger.isInfoEnabled()

  override protected def notifyWarning(message: String): Unit = logger.warn(message)

  override def isErrorEnabled: Boolean = logger.isErrorEnabled()

  override protected def notifyInfo(message: String): Unit = logger.info(message)

  override def isDebugEnabled: Boolean = logger.isDebugEnabled()

  override def isWarningEnabled: Boolean = logger.isWarnEnabled()
}
