package Types.Internal

import Instances.*
import org.apache.pekko.actor.typed.ActorRef

sealed trait MsgT[A, M]
case class Merge[A, M](v: Wcrdt[A, Stream[M]]) extends MsgT[A, M]
case class Process[A, M](m: M, stream: Stream[M]) extends MsgT[A, M]
case class UpdateIdSet[A, M](f: Set[Int] => Set[Int]) extends MsgT[A, M]
case class UpdateRef[A, M](
    f: Map[ProcID, ActorRef[MsgT[A, M]]] => Map[ProcID, ActorRef[MsgT[A, M]]]
) extends MsgT[A, M]
