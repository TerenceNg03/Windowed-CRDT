package Types

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import Types.CRDT
import Instances.{given, *}
import scalaz.Const
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.ActorContext

case class WActorState[A, M](
    val wcrdt: Wcrdt[A, Int],
    val actorId: Int,
    val actorIdSet: Set[Int],
    val actorRefs: Map[Int, ActorRef[MsgT[A, Int, M]]]
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
  )(id: Int)(handle: Handle[A, M]): Behavior[MsgT[A, Int, M]] =
    runWActorT_(
      WActorState(
        summon[CRDT[Wcrdt[A, Int], ?, ?]].bottom(id),
        id,
        Set.empty,
        Map.empty
      )
    )(handle)

  def runWActorT_[A, B, M](using
      x: CRDT[A, B, Int]
  )(s: WActorState[A, M])(handle: Handle[A, M]): Behavior[MsgT[A, Int, M]] =
    Behaviors.receive[MsgT[A, Int, M]]: (context, msg) =>
      msg match
        case Merge(v) =>
          val wcrdt = s.wcrdt \/ v
          context.log.info(
            s"Actor ${s.wcrdt.procID}: Merged\n$v\ninto\n${s.wcrdt}\nresulting value\n$wcrdt"
          )
          runWActorT_(s.copy(wcrdt = wcrdt))(handle)
        case UpdateIdSet(f) =>
          runWActorT_(s.copy(actorIdSet = f(s.actorIdSet)))(handle)
        case UpdateRef(f) =>
          runWActorT_(s.copy(actorRefs = f(s.actorRefs)))(handle)
        case Process(m) =>
          handle(context)(m)(s.wcrdt) match
            case UpdateCRDT(v) =>
              s.actorRefs.foreach((id, ref) =>
                if id != s.actorId then ref ! Merge(v)
              )
              runWActorT_(s.copy(wcrdt = v))(handle)
            case Pass()            => runWActorT_(s)(handle)
            case ModifyBehavior(b) => b
