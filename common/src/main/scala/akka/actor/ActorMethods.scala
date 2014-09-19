package akka.actor

import scala.concurrent.Future


trait ActorMethods {
  
  type ActorState <: Actor

  protected def thisActor: ActorState

  protected implicit def self: ActorRef

}


abstract class ActorRefWithMethods(val actorRef: ActorRef) extends ActorMethods with Serializable


case class ActorMethodCall(methodName: String, args: List[List[Any]],
                           replyTo: ReplyAddress[Nothing] = ReplyAddress.replyToSender(None))


class CurriedActorMethodCall[-T](val methodName: String, val args: List[List[Any]]) extends Serializable {

  def uncurry(value: T) = ActorMethodCall(methodName, args :+ List(value))

}

object DontReply extends CurriedActorMethodCall[Any]("", Nil)


trait Reply[+T] {

  def value: T

  def handleWith(method: ReplyAddress[T])

  def toFuture: Future[T]

  final def ignoreReply(): Unit = handleWith(ReplyAddress.nullAddress)

}

object Reply {

  def apply[T](v: T) = new Reply[T] with ReplyStub[T] {

    override def value = v

  }

}


trait ReplyStub[+T] extends Reply[T] {

  override def handleWith(method: ReplyAddress[T]) = error

  override def toFuture: Future[T] = error

  private def error = throw new RuntimeException("Don't call this method inside the actor which created the ActorReply.")

}


object WillReplyLater extends Reply[Nothing] with ReplyStub[Nothing] {

  override def value = throw new RuntimeException("No value.")

}


class ReplyAddress[-T](val actorRef: Option[ActorRef], val call: Option[CurriedActorMethodCall[T]] = None) extends Serializable {

  def sendReply(value: T): Unit = {
    if (call != Some(DontReply)) {
      actorRef.foreach(_ ! call.map(_.uncurry(value)).getOrElse(value))
    }
  }

  def fillRef(newActorRef: ActorRef): ReplyAddress[T] =
    if (actorRef.isEmpty) new ReplyAddress[T](Some(newActorRef), call)
    else this
}

object ReplyAddress {

  implicit val nullAddress = new ReplyAddress[Any](None) {

    override def sendReply(value: Any): Unit = {}

    override def fillRef(newActorRef: ActorRef): ReplyAddress[Any] = this

  }


  def replyToSender[T](call: Option[CurriedActorMethodCall[T]]) = new ReplyAddress[T](None, call)

}
