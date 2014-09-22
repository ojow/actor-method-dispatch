package ojow.actor

import akka.actor.{ActorRef, Status}

import scala.concurrent.Future

trait Reply[+T] {

  def value: T

  def handleWith(method: ReplyAddress[T], exceptionHandler: ReplyAddress[Status.Status] = ReplyAddress.replyToSender(None))

  def toFuture: Future[T]

  final def ignoreReply(): Unit = handleWith(ReplyAddress.dontReply)

}

object Reply {

  def apply[T](v: T): Reply[T] = new Reply[T] with ReplyStub[T] {

    override def value = v

  }

}


trait ReplyStub[+T] extends Reply[T] {

  override def handleWith(method: ReplyAddress[T], exceptionHandler: ReplyAddress[Status.Status] = ReplyAddress.replyToSender(None)) = error

  override def toFuture: Future[T] = error

  private def error = throw new RuntimeException("Don't call this method inside the actor which created the ActorReply.")

}


object WillReplyLater extends Reply[Nothing] with ReplyStub[Nothing] {

  override def value = throw new RuntimeException("No value.")

}


class ReplyAddress[-T](val actorRef: Option[ActorRef], val call: Option[CurriedActorMethodCall[T]] = None) extends Serializable {

  def sendReply(value: T): Unit = {
    actorRef.foreach(_ ! call.map(_.uncurry(value)).getOrElse(value))
  }

  def fillRef(newActorRef: ActorRef): ReplyAddress[T] =
    if (actorRef.isEmpty) new ReplyAddress[T](Some(newActorRef), call)
    else this
}

object ReplyAddress {

  implicit val dontReply = new ReplyAddress[Any](None) {

    override def sendReply(value: Any): Unit = {}

    override def fillRef(newActorRef: ActorRef): ReplyAddress[Any] = this

  }


  def replyToSender[T](call: Option[CurriedActorMethodCall[T]]) = new ReplyAddress[T](None, call)

}
