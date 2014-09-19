package ojow.actor

import akka.util.Timeout

import language.experimental.macros
import scala.concurrent.ExecutionContext
import scala.reflect.macros.blackbox
import akka.actor._


object ActorMethodDispatchMacros {

  def askMethodPrefix = "ask"

  def tellMethodPrefix = "tell"


  def selfMethods[T <: ActorMethods](implicit ec: ExecutionContext): Actor.Receive = macro selfMethodsImpl[T]

  def selfMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(
                                                              ec: c.Expr[ExecutionContext]): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](methods2cases(c)(tpe, q"this", ec))
  }


  def swappableMethods[T <: ActorMethods](obj: => T)(implicit ec: ExecutionContext): Actor.Receive =
    macro swappableMethodsImpl[T]

  def swappableMethodsImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(obj: c.Tree)(
                                                              ec: c.Expr[ExecutionContext]): c.Expr[Actor.Receive] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    c.Expr[Actor.Receive](q"""
      new PartialFunction[Any, Unit] {
        val methodsObj = $obj
        val recv: Receive = ${methods2cases(c)(tpe, q"methodsObj", ec)}
        override def isDefinedAt(x: Any) = recv.isDefinedAt(x)
        override def apply(v1: Any) = recv.apply(v1)
      }
     """)

  }


  def actorMethodsProxy[T <: ActorMethods](ref: ActorRef)(implicit askTimeout: Timeout,
                                                          ec: ExecutionContext): T = macro actorMethodsProxyImpl[T]

  def actorMethodsProxyImpl[T <: ActorMethods : c.WeakTypeTag](c: blackbox.Context)(ref: c.Expr[ActorRef])(
                                            askTimeout: c.Expr[Timeout], ec: c.Expr[ExecutionContext]): c.Expr[T] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    val (tellMethods, askReplyMethods) = selectMethods(c)(tpe.members)

    def method2override(m: c.universe.MethodSymbol, body: (Tree, Tree) => Tree): Tree = {
      val (params, implicitParams) = paramLists(c)(m)
      val paramsDef = params.map(_.map(sym => q"${sym.name.toTermName}: ${sym.typeSignature}"))
      val implicitParamsDef = implicitParams.map(sym => q"${sym.name.toTermName}: ${sym.typeSignature}")
      val argValues = q"List(..${m.paramLists.map(xs => q"List(..${xs.map(sym => q"${sym.name.toTermName}")})")})"
      val name = Literal(Constant(m.name.decodedName.toString))

      q"override def ${m.name}(...$paramsDef)(implicit ..$implicitParamsDef) = ${body(q"$name", q"$argValues")}"
    }

    val tellOverrides = tellMethods.map(m => method2override(m, (name, argValues) =>
      q"actorRef ! ojow.actor.AmcReplyToSender($name, $argValues)"))

    val askReplyOverrides = askReplyMethods.map(m => method2override(m, (name, argValues) => {
      val typeArgs = m.returnType.typeArgs.map(x => tq"$x")
      q""" new ${m.returnType} {
         override def value = proxyError
         override def handleWith(addr: ojow.actor.ReplyAddress[..$typeArgs],
           exceptionHandler: ReplyAddress[Status.Status] = ReplyAddress.replyToSender(None)): Unit = {
             actorRef ! ojow.actor.AmcWithReplyAddress($name, $argValues, addr, exceptionHandler)
         }
         override def toFuture: scala.concurrent.Future[..$typeArgs] =
           akka.pattern.ask(actorRef, ojow.actor.AmcReplyToSender($name, $argValues))($askTimeout).
             asInstanceOf[scala.concurrent.Future[..$typeArgs]]
      } """}))

    c.Expr[T] {q"""
      new ojow.actor.ActorRefWithMethods($ref) with $tpe {
        private def proxyError = throw new RuntimeException("This method must not be called on a proxy.")
        override protected def thisActor = proxyError
        override protected def self = $ref
        ..${tellOverrides ++ askReplyOverrides}
      }
    """
    }

  }


  def replyHandler[T](f: T => Unit): ReplyAddress[T] = macro replyHandlerImpl[T]

  def replyHandlerImpl[T : c.WeakTypeTag](c: blackbox.Context)(f: c.Expr[T => Unit]): c.Expr[ReplyAddress[T]] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    f.tree match {
      case q"{((${q"$mods val $tname: $tpt = $expr"}) => $selector.${mname: TermName}(...$args)(${lastArg: TermName}))}" if tname == lastArg =>
        val name = Literal(Constant(mname.decodedName.toString))
        c.Expr[ReplyAddress[T]](q"""new ojow.actor.ReplyAddress[$tpe](Some($selector.self), Some(new CurriedActorMethodCall[$tpe]($name, $args)))""")

      case _ => reportError(c, "replyHandler argument must look like a method call without last argument list.")
    }
  }


  private def reportError(c: blackbox.Context, msg: String): Nothing = c.abort(c.enclosingPosition, msg)

  private def paramLists(c: blackbox.Context)(method: c.universe.MethodSymbol): (List[List[c.universe.Symbol]], List[c.universe.Symbol]) = {
    val (params, implicitParamLists) = method.paramLists.partition(_.headOption.exists(! _.isImplicit))
    val implicitParams = implicitParamLists.headOption.getOrElse(Nil)
    (params, implicitParams)
  }

  private def selectMethods(c: blackbox.Context)(members: c.universe.MemberScope):
        (Iterable[c.universe.MethodSymbol], Iterable[c.universe.MethodSymbol]) = {
    import c.universe._
    def nameFilter(s: String): Boolean = s.startsWith(askMethodPrefix) || s.startsWith(tellMethodPrefix)

    val actorMembers = typeOf[Actor].members.map(_.name.decodedName.toString).toSet
    val ms = members.filterNot(m => actorMembers.contains(m.name.decodedName.toString))

    val duplicates = ms.groupBy(m => m.name.decodedName.toString).collect { case (x, ys) if ys.size > 1 => x }
    if (duplicates.nonEmpty) reportError(c, s"Overloading is not supported. Overloaded methods: ${duplicates.mkString(", ")}")

    val methods = ms.collect {
      case x if x.isMethod && x.isPublic =>
        if (nameFilter(x.name.decodedName.toString)) x.asMethod
        else reportError(c,
          s"Illegal method: '${x.name}'. All public methods must either start with '$tellMethodPrefix' or '$askMethodPrefix'.")
    }
    val (tellMethods, askMethods) = methods.partition(_.name.decodedName.toString.startsWith(tellMethodPrefix))

    tellMethods.foreach { m =>
      if (m.returnType != typeOf[Unit])
        reportError(c, s"Method '${m.name}' must have return type Unit.")
    }

    askMethods.foreach { m =>
      if (!(m.returnType <:< typeOf[Reply[_]]))
        reportError(c, s"Method '${m.name}' must return a Reply.")
    }

    (tellMethods, askMethods)
  }

  private def methods2cases(c: blackbox.Context)(tpe: c.universe.Type, selector: c.Tree,
                                                 ec: c.Expr[ExecutionContext]): c.universe.Tree = {
    import c.universe._
    val (tellMethods, askReplyMethods) = selectMethods(c)(tpe.members)

    def method2cases(m: MethodSymbol, methodCallHandler: (Option[c.Tree] => c.Tree, List[Symbol]) => c.Tree): Tree = {
      val (params, implicitParams) = paramLists(c)(m)
      val methodParams = params.zipWithIndex.map {
        case (xs, i) => xs.zipWithIndex.map {
          case (param, j) => q"args($i)($j).asInstanceOf[${param.typeSignature}]"
        }
      }

      val methodNamePattern = pq"${m.name.decodedName.toString}"
      val handler = methodCallHandler(impls => q"$selector.${m.name}(...${impls.map(i => methodParams :+ List(i)).getOrElse(methodParams)})", implicitParams)
      cq"""ojow.actor.ActorMethodCall($methodNamePattern, args, rawReplyAddr, rawExceptionHandler) => $handler"""
    }

    val tellCases = tellMethods.map(method2cases(_, (call, _) => call(None)))

    val askReplyCases = askReplyMethods.map(method2cases(_, (call, implParams) => {
      val replyAddress =  if (implParams.nonEmpty) Some(q"replyAddr") else None // TODO: correct check

      q"""
        val replyAddr = rawReplyAddr.asInstanceOf[ReplyAddress[Any]].fillRef(sender())
        val exceptionHandler = rawExceptionHandler.asInstanceOf[ReplyAddress[Any]].fillRef(sender())
        try {
          val result = ${call(replyAddress)}
          if (result != WillReplyLater) {
            replyAddr.sendReply(result.value)
          }
        }
        catch {
          case e: Exception =>
            exceptionHandler.sendReply(akka.actor.Status.Failure(e))
            throw e
        }
      """}))

    q"{ case ..${tellCases ++ askReplyCases} }"
  }
}


