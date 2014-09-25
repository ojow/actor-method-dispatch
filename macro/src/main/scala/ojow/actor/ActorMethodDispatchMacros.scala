package ojow.actor

import akka.util.Timeout

import language.experimental.macros
import scala.concurrent.ExecutionContext
import scala.reflect.macros.blackbox
import akka.actor._


object ActorMethodDispatchMacros {

  def askMethodPrefix = "ask"

  def tellMethodPrefix = "tell"


  /**
   * Return a set of { case ... => ... } clauses which is a Receive that matches ActorMethodCall messages and
   * calls corresponding methods on 'this'.
   * For example if there is a
   *     trait ActorInterface extends ActorMethods { def tellDoSomething(): Unit = ??? }
   * and we call
   *     selfMethods[ActorInterface]
   * the macro returns
   *     {
   *       case ActorMethodCall("tellDoSomething", args, rawReplyAddr, rawExceptionHandler) => tellDoSomething()
   *     }
   */
  def selfMethods[T <: ActorMethods]: Actor.Receive = macro selfMethodsImpl[T]

  def selfMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](methodsToCases(c)(tpe, q"this"))
  }


  /**
   * Works similar to 'selfMethods' except methods are called on the given object.
   * So the returned expression looks like this:
   *
   *   new PartialFunction[Any, Unit] {
   *     val methodsObj = theGivenObject
   *     val recv: Receive = {
   *       case ActorMethodCall("tellDoSomething", args, rawReplyAddr, rawExceptionHandler) => methodsObj.tellDoSomething()
   *     }
   *     override def isDefinedAt(x: Any) = recv.isDefinedAt(x)
   *     override def apply(v1: Any) = recv.apply(v1)
   *   }
   */
  def swappableMethods[T <: ActorMethods](obj: => T): Actor.Receive =
    macro swappableMethodsImpl[T]

  def swappableMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(obj: c.Tree): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](q"""
      new PartialFunction[Any, Unit] {
        val methodsObj = $obj
        val recv: _root_.akka.actor.Actor.Receive = ${methodsToCases(c)(tpe, q"methodsObj")}
        override def isDefinedAt(x: Any) = recv.isDefinedAt(x)
        override def apply(v1: Any) = recv.apply(v1)
      }
     """)

  }


  /**
   * Returns message that is used to call the given method
   */
  def msgFor(method: => Any): AmcReplyToSender = macro msgForImpl

  def msgForImpl(c: blackbox.Context)(method: c.Tree): c.Expr[AmcReplyToSender] = {
    import c.universe._

    method match {
      case q"$selector.$name(...$params)" =>
        val args = q"List(..${params.map(xs => q"List(..$xs)")})"
        val nameLiteral = Literal(Constant(name.decodedName.toString))
        c.Expr[AmcReplyToSender](q"_root_.ojow.actor.AmcReplyToSender($nameLiteral, $args)")

      case _ => reportError(c, "msgFor's argument must be a method call.")
    }
  }

  /**
   * Returns an anonymous class instantion expression. The class is the given 'T' with methods (suitable for message
   * dispatching) overriden with code than makes it possible to send messages to the given ActorRef.
   * For example if there is a
   *     trait ActorInterface extends ActorMethods {
   *       def tellDoSomething(): Unit = ???
   *       def askGetSomething = Reply(1)
   *     }
   * and we call
   *     actorMethodsProxy[ActorInterface](someActor)
   * the macro returns the following expression:
   *     new ActorInterface {
   *       override def tellDoSomething(): Unit = { someActor ! AmcReplyToSender("tellDoSomething", List(List()))
   *       override def askGetSomething: Reply[Int] = new Reply[Int] {
   *         override def value = proxyError
   *         override def handleWith(addr: ReplyAddress[Int], exceptionHandler: ReplyAddress[Status.Status] = ReplyAddress.replyToSender(None)): Unit = {
   *             actorRef ! AmcWithReplyAddress("askGetSomething", Nil, addr, exceptionHandler)
   *         }
   *         override def toFuture: Future[Int] = ask(actorRef, AmcReplyToSender("askGetSomething", Nil))(askTimeout).asInstanceOf[Future[Int]]
   *       }
   *     }
   */
  def actorMethodsProxy[T <: ActorMethods](ref: ActorRef)(implicit askTimeout: Timeout,
                                                          ec: ExecutionContext): T = macro actorMethodsProxyImpl[T]

  def actorMethodsProxyImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(ref: c.Expr[ActorRef])(
                                            askTimeout: c.Expr[Timeout], ec: c.Expr[ExecutionContext]): c.Expr[T] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    val (tellMethods, askReplyMethods, protectedAbstractMethods) = selectMethods(c)(tpe.members)

    def method2override(m: c.universe.MethodSymbol, body: (Tree, Tree) => Tree): Tree = {
      val (paramss, implicitParams) = paramLists(c)(m)
      val paramsDef = paramss.map(_.map(sym => q"${sym.name.toTermName}: ${sym.typeSignature}"))
      val implicitParamsDef = implicitParams.map(sym => q"${sym.name.toTermName}: ${sym.typeSignature}")
      val argValues = q"List(..${m.paramLists.map(xs => q"List(..${xs.map(sym => q"${sym.name.toTermName}")})")})"
      val name = Literal(Constant(m.name.decodedName.toString))

      q"override def ${m.name}(...$paramsDef)(implicit ..$implicitParamsDef) = ${body(q"$name", q"$argValues")}"
    }

    val protectedAbstractOverrides = protectedAbstractMethods.map(m => method2override(m, (_, _) => q"proxyError"))

    val tellOverrides = tellMethods.map(m => method2override(m, (name, argValues) =>
      q"actorRef ! _root_.ojow.actor.AmcReplyToSender($name, $argValues)"))

    val askReplyOverrides = askReplyMethods.map(m => method2override(m, (name, argValues) => {
      val typeArgs = m.returnType.typeArgs.map(x => tq"$x")
      q""" new ${m.returnType} {
         override def value = proxyError
         override def handleWith(addr: _root_.ojow.actor.ReplyAddress[..$typeArgs],
           exceptionHandler: _root_.ojow.actor.ReplyAddress[_root_.ojow.actor.Status.Status] = _root_.ojow.actor.ReplyAddress.replyToSender(None)): Unit = {
             actorRef ! _root_.ojow.actor.AmcWithReplyAddress($name, $argValues, addr, exceptionHandler)
         }
         override def toFuture: _root_.scala.concurrent.Future[..$typeArgs] =
           _root_.akka.pattern.ask(actorRef, _root_.ojow.actor.AmcReplyToSender($name, $argValues))($askTimeout).
             asInstanceOf[_root_.scala.concurrent.Future[..$typeArgs]]
      } """}))

    c.Expr[T] {q"""
      new _root_.ojow.actor.ActorRefWithMethods($ref) with $tpe {
        private def proxyError = throw new _root_.java.lang.RuntimeException("This method must not be called on a proxy.")
        override protected def actor = proxyError
        override protected def self = $ref
        ..${tellOverrides ++ askReplyOverrides ++ protectedAbstractOverrides}
      }
    """
    }

  }

  /**
   * Converts a method call without last parameter list to creation of a corresponding ReplyAddress.
   * For example if there is a
   *     def tellAcceptInt(context: MyContext)(value: Int)
   * and we call
   *     replyHandler(actorMethods.tellAcceptInt(myContext))
   * we get
   *     new ReplyAddress(Some(actorMethods.self), Some(new CurriedActorMethodCall("tellAcceptInt", List(List(context)))))
   */
  def replyHandler[T](f: T => Unit): ReplyAddress[T] = macro replyHandlerImpl[T]

  def replyHandlerImpl[T : c.WeakTypeTag](c: blackbox.Context)(f: c.Expr[T => Unit]): c.Expr[ReplyAddress[T]] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    f.tree match {
      case q"{((${q"$mods val $tname: $tpt = $expr"}) => $selector.${mname: TermName}(...$args)(${lastArg: TermName}))}" if tname == lastArg =>
        val name = Literal(Constant(mname.decodedName.toString))
        c.Expr[ReplyAddress[T]](
          q"""new _root_.ojow.actor.ReplyAddress[$tpe](Some($selector.self),
             Some(new _root_.ojow.actor.CurriedActorMethodCall[$tpe]($name, $args)))""")

      case _ => reportError(c, "replyHandler argument must look like a method call without last argument list.")
    }
  }


  private def reportError(c: blackbox.Context, msg: String): Nothing = c.abort(c.enclosingPosition, msg)

  /**
   * Partitions regular and implicit parameters of a method
   */
  private def paramLists(c: blackbox.Context)(method: c.universe.MethodSymbol): (List[List[c.universe.Symbol]], List[c.universe.Symbol]) = {
    val (params, implicitParamLists) = method.paramLists.partition(_.headOption.exists(! _.isImplicit))
    val implicitParams = implicitParamLists.headOption.getOrElse(Nil)
    (params, implicitParams)
  }

  /**
   * Selects and validates methods suitable for message dispatching
   */
  private def selectMethods(c: blackbox.Context)(members: c.universe.MemberScope):
        (Iterable[c.universe.MethodSymbol], Iterable[c.universe.MethodSymbol], Iterable[c.universe.MethodSymbol]) = {
    import c.universe._
    def nameFilter(s: String): Boolean = s.startsWith(askMethodPrefix) || s.startsWith(tellMethodPrefix)

    val actorMembers = typeOf[Actor].members.map(_.name.decodedName.toString).toSet
    val ms = members.filterNot(m => actorMembers.contains(m.name.decodedName.toString))

    val duplicates = ms.groupBy(m => m.name.decodedName.toString).collect { case (x, ys) if ys.size > 1 => x }
    if (duplicates.nonEmpty) reportError(c, s"Overloading is not supported. Overloaded methods: ${duplicates.mkString(", ")}")

    val reservedMethodNames = Set("self", "actor")
    val protectedAbstractMethods = ms.collect {
      case x if x.isMethod && x.isProtected && x.isAbstract &&
        !reservedMethodNames.contains(x.name.decodedName.toString) => x.asMethod
    }

    val publicMethods = ms.collect {
      case x if x.isMethod && x.isPublic =>
        if (nameFilter(x.name.decodedName.toString)) x.asMethod
        else reportError(c,
          s"Illegal method: '${x.name}'. All public methods must either start with '$tellMethodPrefix' or '$askMethodPrefix'.")
    }
    val (tellMethods, askMethods) = publicMethods.partition(_.name.decodedName.toString.startsWith(tellMethodPrefix))

    tellMethods.foreach { m =>
      if (m.returnType != typeOf[Unit])
        reportError(c, s"Method '${m.name}' must have return type Unit.")
    }

    askMethods.foreach { m =>
      if (!(m.returnType <:< typeOf[Reply[_]]))
        reportError(c, s"Method '${m.name}' must return a Reply.")
    }

    (tellMethods, askMethods, protectedAbstractMethods)
  }

  /**
   * Converts suitable for message dispatching methods of type 'tpe' to a set of { case ... => ... } clauses
   * which is a Receive that matches ActorMethodCall messages and calls corresponding methods
   */
  private def methodsToCases(c: blackbox.Context)(tpe: c.universe.Type, selector: c.Tree): c.universe.Tree = {
    import c.universe._
    val (tellMethods, askReplyMethods, _) = selectMethods(c)(tpe.members)

    def methodToCase(m: MethodSymbol, methodCallHandler: (Option[c.Tree] => c.Tree, List[Symbol]) => c.Tree): Tree = {
      val (params, implicitParams) = paramLists(c)(m)
      val methodParams = params.zipWithIndex.map {
        case (xs, i) => xs.zipWithIndex.map {
          case (param, j) => q"args($i)($j).asInstanceOf[${param.typeSignature}]"
        }
      }

      val methodNamePattern = pq"${m.name.decodedName.toString}"
      val handler = methodCallHandler(impls => q"$selector.${m.name}(...${impls.map(i => methodParams :+ List(i)).getOrElse(methodParams)})", implicitParams)
      cq"""_root_.ojow.actor.ActorMethodCall($methodNamePattern, args, rawReplyAddr, rawExceptionHandler) => $handler"""
    }

    val tellCases = tellMethods.map(methodToCase(_, (call, _) => call(None)))

    val askReplyCases = askReplyMethods.map(methodToCase(_, (call, implParams) => {
      val replyAddress =  if (implParams.nonEmpty) Some(q"replyAddr") else None // TODO: correct check

      q"""
        val replyAddr = rawReplyAddr.asInstanceOf[_root_.ojow.actor.ReplyAddress[Any]].fillRef(sender())
        val exceptionHandler = rawExceptionHandler.asInstanceOf[_root_.ojow.actor.ReplyAddress[Any]].fillRef(sender())
        try {
          val result = ${call(replyAddress)}
          if (result != _root_.ojow.actor.WillReplyLater) {
            replyAddr.sendReply(result.value)
          }
        }
        catch {
          case e: _root_.java.lang.Exception =>
            exceptionHandler.sendReply(_root_.akka.actor.Status.Failure(e))
            throw e
        }
      """}))

    q"{ case ..${tellCases ++ askReplyCases} }"
  }
}


