package ojow.actor

import akka.testkit.{EventFilter, TestEvent}
import akka.util.Timeout
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import akka.actor._
import ActorMethodDispatchMacros._
import scala.concurrent.ExecutionContext.Implicits.global


class AskExceptionTest extends FunSuite with ScalaFutures {
  import AskExceptionTest._

  test("Exception in an 'ask' method") {
    val sys = ActorSystem("Test")

    implicit val t = Timeout(1.second)

    val myActor = actorMethodsProxy[CounterActorInterface](sys.actorOf(Props[CounterActor]))

    // Exceptions inside 'ask' methods are automatically passed back
    val exceptionResult = myActor.askException.toFuture
    whenReady(exceptionResult.failed) { e =>
      assert(e.isInstanceOf[IllegalStateException])
    }

    sys.shutdown()
  }
}


object AskExceptionTest {

  class CounterActor extends Actor {

    override def receive = swappableMethods(new LinkedTo(this) with CounterActorInterface)

  }


  trait CounterActorInterface extends ActorMethodsOf[CounterActor] {

    def askException: Reply[Int] = throw new IllegalStateException("It is happening again.")

  }

}

