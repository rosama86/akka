/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import scala.annotation.{ tailrec, switch }
import scala.util.control.NonFatal
import scala.util.control.Exception.Catcher
import akka.event.Logging

/**
 * INTERNAL API
 */
private[typed] trait SupervisionMechanics[T] {
  import ActorCell._

  /*
   * INTERFACE WITH ACTOR CELL
   */
  protected def system: ActorSystemImpl[Nothing]
  protected def props: Props[T]
  protected def self: ActorRefImpl[T]
  protected def behavior: Behavior[T]
  protected def behavior_=(b: Behavior[T]): Unit
  protected def next(b: Behavior[T], msg: Any): Unit
  protected def childrenMap: Map[String, ActorRefImpl[Nothing]]
  protected def terminatingMap: Map[String, ActorRefImpl[Nothing]]
  protected def stopAll(): Unit
  protected def getStatus: Int
  protected def ctx: ActorContext[T]
  protected def publish(e: Logging.LogEvent): Unit
  protected def clazz(obj: AnyRef): Class[_]

  /**
   * Process one system message and return whether further messages shall be processed.
   */
  protected def processSignal(message: SystemMessage): Boolean =
    try {
      message match {
        case Watch(watchee, watcher)       ⇒ true //addWatcher(watchee, watcher)
        case Unwatch(watchee, watcher)     ⇒ true //remWatcher(watchee, watcher)
        case DeathWatchNotification(a, at) ⇒ true //watchedActorTerminated(a, at)
        case Create()                      ⇒ create()
        case Terminate()                   ⇒ terminate()
        case NoMessage                     ⇒ false // only here to suppress warning
      }
    } catch handleNonFatalOrInterruptedException { e ⇒
      //handleInvokeFailure(Nil, e)
    }

  private var _failed: Throwable = null
  protected def failed: Throwable = _failed

  private def handleNonFatalOrInterruptedException(thunk: (Throwable) ⇒ Unit): Catcher[Boolean] = {
    case e: InterruptedException ⇒
      thunk(e)
      Thread.currentThread.interrupt()
      false
    case NonFatal(e) ⇒
      thunk(e)
      false
  }

  private def create(): Boolean = {
    behavior = props.creator()
    next(behavior.management(ctx, PreStart), PreStart)
    true
  }

  // this might want to be folded into the status field
  private val _isTerminating: Boolean = false
  protected def isTerminating: Boolean = _isTerminating

  private def terminate(): Boolean = {
    unwatchWatchedActors()
    stopAll()
    if (terminatingMap.isEmpty) {
      finishTerminate()
      false
    } else true
  }

  private def finishTerminate() {
    val a = behavior
    /* The following order is crucial for things to work properly. Only change this if you're very confident and lucky.
     *
     * Please note that if a parent is also a watcher then ChildTerminated and Terminated must be processed in this
     * specific order.
     */
    try if (a ne null) a.management(ctx, PostStop)
    catch handleNonFatalOrInterruptedException { e ⇒ publish(Logging.Error(e, self.path.toString, clazz(a), e.getMessage)) }
    //finally try stopFunctionRefs()
    finally try tellWatchersWeDied()
    finally try unwatchWatchedActors() // stay here as we expect an emergency stop from handleInvokeFailure
    finally {
      if (system.settings.DebugLifecycle)
        publish(Logging.Debug(self.path.toString, clazz(a), "stopped"))

      behavior = null
    }
  }

  private var watching = Set.empty[ActorRefImpl[Nothing]]
  private var watchers = Set.empty[ActorRefImpl[Nothing]]

  private def tellWatchersWeDied(): Unit = {

  }

  private def unwatchWatchedActors(): Unit = {

  }
}
