package Types.Internal

import Instances.ProcID
import Instances.Wcrdt
import Instances.given
import Types.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

case class ActorState[A, M](
    val wcrdt: Wcrdt[A, Stream[M]],
    val actorIdSet: Set[ProcID],
    val actorRefs: Map[Int, ActorRef[MsgT[A, M]]],
    // Awaits: #Window, Message waiting, Monad Operation to be continued, Following Messages
    val queuedHandleM: Option[(Int, M, A => HandleM[A, M, Unit])],
    val currentStream: Stream[M]
):
  def actorId: ProcID = wcrdt.procID.v

/** Windowed CRDT Actor Transformer
  */
object Actor:
  /** Run a windowed crdt actor transformer
    *
    * Behavior can be modified by returning Right in handle
    *
    * @param x
    * @param wcrdt
    * @param handle
    * @return
    */
  def runActor[A, M](using
      x: CRDT[A]
  )(initCRDT: A)(
      procID: ProcID
  )(handle: HandleM[A, M, Unit])(stream: Stream[M]): Behavior[MsgT[A, M]] =
    runActor_(
      ActorState(
        Wcrdt.newWcrdt[A, Stream[M]](procID)(initCRDT),
        Set.empty,
        Map.empty,
        None,
        stream
      )
    )(handle)

  def runActor_[A, M](using
      x: CRDT[A]
  )(s: ActorState[A, M])(
      handle: HandleM[A, M, Unit]
  ): Behavior[MsgT[A, M]] =
    val processResult =
      (x: HandleResult[A, M, Unit]) =>
        x match
          // Continue handle next message
          case Continue(s, _) => runActor_(s._2)(handle)
          // Backdoor to replace behavior
          case ModifyBehavior(b) => b
          // Waiting for a window, later operation queued
          case AwaitWindow(w, msg, s, next) =>
            val s_ = s._2.copy(queuedHandleM = Some(w, msg, next))
            runActor_(s_)(handle)

    Behaviors.receive[MsgT[A, M]]: (context, msg) =>
      msg match
        case Merge(v) =>
          val wcrdt = s.wcrdt \/ v
          val s_ = s.copy(wcrdt = wcrdt)
          context.log.debug(
            s"Actor ${s.actorId} (finished#${s.wcrdt.window.v - 1})" +
              s" is merging from Actor ${v.procID.v} (finished#${v.window.v - 1})"
          )
          // Check if we had the window value if there is an await
          // Resume execution if we had
          s_.queuedHandleM match
            case None => runActor_(s_)(handle)
            case Some(w, m, hm) =>
              s_.wcrdt.query(w)(s_.actorIdSet) match
                case None =>
                  context.log.debug(
                    s"Actor ${s.actorId} is still waiting for window#$w"
                  )
                  runActor_(s_)(handle)
                case Some(crdt) =>
                  context.log.debug(
                    s"Actor ${s.actorId} is continuing, previously waiting for window#$w"
                  )
                  val result =
                    hm(crdt).runHandleM(
                      m,
                      s.copy(queuedHandleM = None),
                      context
                    )
                  processResult(result)
        case UpdateIdSet(f) =>
          runActor_(s.copy(actorIdSet = f(s.actorIdSet)))(handle)
        case UpdateRef(f) =>
          runActor_(s.copy(actorRefs = f(s.actorRefs)))(handle)
        case Process(m, stream) =>
          context.log.debug(s"Actor ${s.actorId} gets a new message: $m")
          // If there are awaits, postpone message handling
          s.queuedHandleM match
            case Some(w, m_, hm) =>
              context.log.debug(
                s"Actor ${s.actorId} is waiting. New message will be queued up."
              )
              val s_ = s.copy(queuedHandleM =
                Some(
                  w,
                  m_,
                  x => hm(x) >> HandleM.prepareHandleNewMsg(m)(stream) >> handle
                )
              )
              runActor_(s_)(handle)
            case None =>
              val result = (HandleM.prepareHandleNewMsg(m)(stream) >> handle)
                .runHandleM(m, s, context)
              processResult(result)
