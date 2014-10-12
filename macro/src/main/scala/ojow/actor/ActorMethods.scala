package ojow.actor

import akka.actor.{Actor, ActorRef}


trait ActorMethods {
  
  type ActorState <: Actor

  protected def actor: ActorState

  protected implicit def self: ActorRef

  protected def proxyError = throw new RuntimeException("This method must not be called on a proxy.")

}


trait ActorMethodsOf[T <: Actor] extends ActorMethods {

  override type ActorState = T

}





