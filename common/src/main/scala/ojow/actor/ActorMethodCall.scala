package ojow.actor

import akka.actor.Status

trait ActorMethodCall extends Serializable {

  val methodName: String

  val args: List[List[Any]]

  def replyTo: ReplyAddress[Nothing]

  def exceptionHandler: ReplyAddress[Status.Status]

  def isEmpty: Boolean = false

  def get: ActorMethodCall = this

  def _1: String = methodName

  def _2: List[List[Any]] = args

  def _3: ReplyAddress[Nothing] = replyTo

  def _4: ReplyAddress[Status.Status] = exceptionHandler

}

object ActorMethodCall {

  def unapply(call: ActorMethodCall) = call

}


case class AmcReplyToSender(methodName: String, args: List[List[Any]]) extends ActorMethodCall {

  override def replyTo: ReplyAddress[Nothing] = ReplyAddress.replyToSender(None)

  override def exceptionHandler: ReplyAddress[Status.Status] = ReplyAddress.replyToSender(None)

}


case class AmcWithReplyAddress(methodName: String, args: List[List[Any]],
                               replyTo: ReplyAddress[Nothing], exceptionHandler: ReplyAddress[Status.Status]) extends ActorMethodCall


case class CurriedActorMethodCall[-T](methodName: String, args: List[List[Any]]) {

  def uncurry(value: T) = AmcReplyToSender(methodName, args :+ List(value))

}
