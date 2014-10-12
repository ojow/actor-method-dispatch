package ojow.actor

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

    val myActor = actorMethodsProxy[ExceptionActorInterface](sys.actorOf(Props[ExceptionActor]))

    // Exceptions inside 'ask' methods are automatically passed back
    val exceptionResult = myActor.askException.toFuture
    whenReady(exceptionResult.failed) { e =>
      assert(e.isInstanceOf[IllegalStateException])
    }


    //methodRefIO(myActor.askTest("a")(1))
    //methodRef((x: Long) => myActor.askTest("a")(1)(x))

    //val a = AMC[Int, Unit](null, Array(), Array(), None)
    //a("a")

    sys.shutdown()
  }
}


object AskExceptionTest {

  class ExceptionActor extends Actor {

    override def receive = swappableMethods(new LinkedTo(this) with ExceptionActorInterface)

  }


  trait ExceptionActorInterface extends ActorMethodsOf[ExceptionActor] {

    def askException: Reply[Int] = throw new IllegalStateException("It is happening again.")

    def askTest(s: String)(i: Int)(l: Long): Reply[Int] = ???

  }

}

