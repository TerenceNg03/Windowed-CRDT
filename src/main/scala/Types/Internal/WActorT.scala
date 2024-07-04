package Types.Internal

import Instances.{*, given}
import Types.*
import Types.CRDT
import Types.given
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scalaz.IndexedStateT.StateMonadTrans
import scalaz.MonadTrans
import scalaz.Scalaz.ToBindOps

case class WActorState[A, M](
    val wcrdt: Wcrdt[A, Int],
    val actorId: Int,
    val actorIdSet: Set[Int],
    val actorRefs: Map[Int, ActorRef[MsgT[A, Int, M]]],
    // Awaits: #Window, Message waiting, Monad Operation to be continued, Following Messages
    val queuedHandleM: Option[(Int, M, A => HandleM[A, M, Unit])]
)

/** Windowed CRDT Actor Transformer
  */
object WActorT:
  /** Run a windowed crdt actor transformer
    *
    * Behavior can be modified by returning Right in handle
    *
    * @param x
    * @param wcrdt
    * @param handle
    * @return
    */
  def runWActorT[A, B, M](using
      x: CRDT[A, B, Int]
  )(id: Int)(handle: HandleM[A, M, Unit]): Behavior[MsgT[A, Int, M]] =
    runWActorT_(
      WActorState(
        summon[CRDT[Wcrdt[A, Int], ?, ?]].bottom(id),
        id,
        Set.empty,
        Map.empty,
        None
      )
    )(handle)

  def runWActorT_[A, B, M](using
      x: CRDT[A, B, Int]
  )(s: WActorState[A, M])(
      handle: HandleM[A, M, Unit]
  ): Behavior[MsgT[A, Int, M]] =
    val processResult = (x: HandleResult[A, M, Unit]) =>
      x match
        case UpdateCRDT(v, _) =>
          // BroadCast finished window
          if v.window > s.wcrdt.window then
            s.actorRefs.foreach((id, ref) =>
              if id != s.actorId then ref ! Merge(v)
            )
          runWActorT_(s.copy(wcrdt = v))(handle)
        // No update to CRDT
        case Pass(_) => runWActorT_(s)(handle)
        // Backdoor to replace behavior
        case ModifyBehavior(b) => b
        // Waiting for a window, later operation queued
        case AwaitWindow(w, msg, crdt, next) =>
          val s_ = s.copy(
            wcrdt = crdt,
            queuedHandleM =
              Some((w, msg, x => summon[MonadTrans[?]].liftM(next(x))))
          )
          runWActorT_(s_)(handle)

    Behaviors.receive[MsgT[A, Int, M]]: (context, msg) =>
      msg match
        case Merge(v) =>
          val wcrdt = s.wcrdt \/ v
          val s_ = s.copy(wcrdt = wcrdt)
          // Check if we had the window value if there is an await
          // Resume execution if we had
          s_.queuedHandleM match
            case None => runWActorT_(s_)(handle)
            case Some(w, m, hm) =>
              s_.wcrdt.query(w)(s_.actorIdSet) match
                case None => runWActorT_(s_)(handle)
                case Some(crdt) =>
                  val result =
                    hm(crdt).eval((context, m, s_)).runHandleM_(s_.wcrdt)
                  processResult(result)
        case UpdateIdSet(f) =>
          runWActorT_(s.copy(actorIdSet = f(s.actorIdSet)))(handle)
        case UpdateRef(f) =>
          runWActorT_(s.copy(actorRefs = f(s.actorRefs)))(handle)
        case Process(m) =>
          // If there are awaits, postpone message handling
          s.queuedHandleM match
            case Some(w, m_, hm) =>
              val s_ = s.copy(queuedHandleM =
                Some(
                  w,
                  m_,
                  x => hm(x) >> HandleM.transformMsg(_ => m) >> handle
                )
              )
              runWActorT_(s_)(handle)
            case None =>
              val result = handle.eval((context, m, s)).runHandleM_(s.wcrdt)
              processResult(result)
