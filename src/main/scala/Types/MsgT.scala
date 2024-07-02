package Types

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import Types.CRDT
import Instances.{given, *}

sealed trait MsgT[A, C, M]
case class Merge[A, C, M](v: Wcrdt[A, C]) extends MsgT[A, C, M]
case class Process[A, C, M](m: M) extends MsgT[A, C, M]
case class UpdateIdSet[A, C, M](f: Set[Int] => Set[Int]) extends MsgT[A, C, M]
case class UpdateRef[A, C, M](
    f: Map[C, ActorRef[MsgT[A, Int, M]]] => Map[C, ActorRef[MsgT[A, Int, M]]]
) extends MsgT[A, C, M]