import sbt.{TestEvent, TestReportListener}
import sbt.protocol.testing.TestResult

object CiTestProgress extends TestReportListener {
  private def report(event: String, name: String): Unit = {
    System.err.println(s"[$event] $name")
    System.err.flush()
  }

  override def startGroup(name: String): Unit = report("suite-start", name)
  override def testEvent(event: TestEvent): Unit = ()
  override def endGroup(name: String, result: TestResult): Unit = report("suite-end", name)
  override def endGroup(name: String, error: Throwable): Unit = report("suite-error", name)
}
