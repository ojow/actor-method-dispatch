package ojow.actor

import akka.util.Timeout
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import akka.actor._
import ActorMethodDispatchMacros._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future


class BasicActorMethodDispatchTest extends FunSuite with ScalaFutures {
  test("Simple test") {
    val sys = ActorSystem("Test")

    // proxy's ask timeout
    implicit val t = Timeout(1.second)

    // Create a proxy which is then used to send messages via method calls
    val myActor = actorMethodsProxy[SimpleActorInterface](sys.actorOf(Props[SimpleActor]))

    // Calling a 'tell' method, return type Unit
    myActor.tellIncrement()

    // Calling an 'ask' method, returns a Reply which can be converted to a Future
    val result: Future[Int] = myActor.askCurrentValue.toFuture
    assert(result.futureValue == 1)

    // Another 'tell' method (calls 'become')
    myActor.tellBecomeModified()

    // After 'become' this one increments the counter by 2
    myActor.tellIncrement()
    assert(myActor.askCurrentValue.toFuture.futureValue == 3)

    // The reply message will not be sent at all
    myActor.askCurrentValue.ignoreReply()

    // Exceptions inside 'ask' methods are automatically passed back
    val exceptionResult = myActor.askException.toFuture
    whenReady(exceptionResult.failed) { e =>
      assert(e.isInstanceOf[IllegalStateException])
    }

    sys.shutdown()
  }
}


class SimpleActor extends Actor with SimpleActorInterface {

  // this Actor's internal state: a counter
  var i: Int = 0

  // give SimpleActorInterface's methods access to the Actor object
  override protected def actor = this

  // selfMethods returns a Receive that routes messages to this Actor method calls
  override def receive = selfMethods[SimpleActorInterface] orElse {
    case m => println(m) // you can still handle messages as usual
  }

  // swappableMethods returns Receive that routes messages to its own instance of ActorMethods
  // LinkedTo(this) is here just to reduce boilerplate so you don't have to override 'actor' manually
  def modifiedBehavior(step: Int): Receive = swappableMethods(new LinkedTo(this) with SimpleActorInterface {
    override def tellIncrement(): Unit = { actor.i += step }
  })

}


// Only allowed public methods are a) starting with 'tell' and returning a Reply, b) starting with 'ask' and returning Unit
trait SimpleActorInterface extends ActorMethodsOf[SimpleActor] {

  def tellIncrement(): Unit = { actor.i += 1 }

  def askCurrentValue = Reply(actor.i)

  def askException: Reply[Int] = throw new IllegalStateException("It is happening again.")

  def tellBecomeModified(): Unit = {
    actor.context.become(actor.modifiedBehavior(2))
  }

}
