package Types.Internal

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import Types.CRDT
import Instances.{given, *}
import scalaz.Const
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import Types._
import Types.given
import scalaz.MonadTrans
import scalaz.Scalaz.ToBindOps
import scalaz.Scalaz.ToBindOpsUnapply
import scalaz.IndexedStateT.StateMonadTrans

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
      (m: M) =>
        x match
          case UpdateCRDT(v, _) =>
            s.actorRefs.foreach((id, ref) =>
              if id != s.actorId then ref ! Merge(v)
            )
            runWActorT_(s.copy(wcrdt = v))(handle)
          case Pass(_)           => runWActorT_(s)(handle)
          case ModifyBehavior(b) => b
          case AwaitWindow(w, crdt, next) =>
            val s_ = s.copy(
              wcrdt = crdt,
              queuedHandleM =
                Some((w, m, x => summon[MonadTrans[?]].liftM(next(x))))
            )
            runWActorT_(s_)(handle)

    val continue = (s: WActorState[A, M]) =>
      (context: ActorContext[MsgT[A, Int, M]]) =>
        s.queuedHandleM match
          case None => s
          case Some(w, m, hm) =>
            s.wcrdt.query(w)(s.actorIdSet) match
              case None       => s
              case Some(crdt) => hm(crdt).eval((context, m)).runHandleM_(s.wcrdt) match
                case ???
              

    Behaviors.receive[MsgT[A, Int, M]]: (context, msg) =>
      msg match
        case Merge(v) =>
          val wcrdt = s.wcrdt \/ v
          context.log.info(
            s"Actor ${s.wcrdt.procID}: Merged\n$v\ninto\n${s.wcrdt}\nresulting value\n$wcrdt"
          )

          // Check if we had the window value if there is an await
          // Resume execution if we had
          // TODO: Need to be recursive in case of multiple awaits
          val s_ = continue(s.copy(wcrdt = wcrdt))
          ???
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
              val result = handle.eval((context, m)).runHandleM_(s.wcrdt)
              processResult(result)(m)
