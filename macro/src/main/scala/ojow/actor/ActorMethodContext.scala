package ojow.actor

import akka.actor.{Actor, Status, ActorRef}


case class ActorMethodContext[-I](sender: ActorRef, message: ActorMethod) {

  def sendReply(value: I)(implicit sdr: ActorRef = Actor.noSender): Unit = message.sendReply(sender, value)

  def sendException(failure: Status.Failure)(implicit sdr: ActorRef = Actor.noSender): Unit =
    message.sendException(sender, failure)

}


object ActorMethodContext {

  implicit object NoContext extends ActorMethodContext[Any](null, null)

}


