package ojow.actor

import akka.actor.{Actor, ActorRef}


trait ActorMethods {
  
  type ActorState <: Actor

  implicit def self: ActorRef

  protected def actor: ActorState

}


trait ActorMethodsProxy extends ActorMethods {

  protected def proxyError = throw new RuntimeException("This method must not be called on a proxy.")

  override protected def actor = proxyError

}


trait ActorMethodsOf[T <: Actor] extends ActorMethods {

  override type ActorState = T

}





