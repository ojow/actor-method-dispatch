
package ojow.actor

import akka.actor._
import akka.util.Timeout
import org.scalatest.FunSuite
import ActorMethodDispatchMacros._
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * @author <a href="mailto:ojowoo@gmail.com">Oleg Galako</a>, 05.10.2014
 */
class UncurryTest extends FunSuite with ScalaFutures {
  import UncurryTest._

  test("Combining via uncurry") {
    val sys = ActorSystem("Test")
    implicit val t = Timeout(1.second)

    val userProvider = actorMethodsProxy[UserProviderActorMethods](sys.actorOf(Props[UserProviderActor]))

    val homePageActor = actorMethodsProxy[HomePageActorMethods](sys.actorOf(Props(classOf[HomePageActor], userProvider)))

    println("END RESULT = " + homePageActor.askProcessRequest(WebRequest("/homepage")).toFuture.futureValue)

    sys.shutdown()
  }

}


object UncurryTest {

  case class WebRequest(url: String)

  case class WebResponse(text: String)

  case class User(name: String)


  trait RequestHandlerActorMethods[T <: Actor] extends ActorMethodsOf[T] {

    def askProcessRequest(request: WebRequest)(implicit replyTo: ActorMethodContext[WebResponse]): Reply[WebResponse]

  }


  trait LoggedInRequestHandlerActor extends Actor {

    def userProvider: UserProviderActorMethods

  }


  trait LoggedInRequestHandlerActorMethods[T <: LoggedInRequestHandlerActor] extends ActorMethodsOf[T] {

    protected def provideUserForRequestHandler(handler: IOAMR[(User, WebRequest), WebResponse]):
                                                                                      IOAMR[WebRequest, WebResponse] = {
      methodRefIO(askRequestWithUserHandler(handler))
    }

    def askRequestWithUserHandler(handler: IOAMR[(User, WebRequest), WebResponse])(request: WebRequest)(implicit
                                                       replyTo: ActorMethodContext[WebResponse]): Reply[WebResponse] = {
      actor.userProvider.askGetUserForRequest(request).handleReply(methodRefI(
        tellRequestWithUserApplyUser(handler, request, replyTo)))
      WillReplyLater
    }

    def tellRequestWithUserApplyUser(handler: IOAMR[(User, WebRequest), WebResponse], request: WebRequest,
                                     replyTo: ActorMethodContext[WebResponse])(user: User): Unit = {
      handler.uncurry(user).uncurry(request).pipeTo(replyTo).run()
    }

  }


  trait HomePageActorMethods extends RequestHandlerActorMethods[HomePageActor] with
                                                                     LoggedInRequestHandlerActorMethods[HomePageActor] {

    override def askProcessRequest(request: WebRequest)(implicit replyTo: ActorMethodContext[WebResponse]): Reply[WebResponse] = {
      provideUserForRequestHandler(methodRefIO(askRenderHomePage)).uncurry(request).pipeTo(replyTo).run()
      WillReplyLater
    }

    def askRenderHomePage(params: (User, WebRequest)): Reply[WebResponse] = params match {
      case (user, request) => Reply(WebResponse(s"Home page. request = $request, user = $user"))
    }

  }


  class UserProviderActor extends Actor {

    override def receive = swappableMethods(new LinkedTo(this) with UserProviderActorMethods)

  }

  trait UserProviderActorMethods extends ActorMethodsOf[UserProviderActor] {

    def askGetUserForRequest(request: WebRequest): Reply[User] = Reply(User("Jim"))

  }


  class HomePageActor(val userProvider: UserProviderActorMethods) extends LoggedInRequestHandlerActor {

    override def receive = swappableMethods(new LinkedTo(this) with HomePageActorMethods)

  }


}