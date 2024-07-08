package Types.Internal

import Instances.*
import Types.HandleM
import org.apache.pekko.actor.typed.ActorRef

sealed trait MsgT[A, M]
case class Merge[A, M](ids: Set[ProcId], v: SharedWcrdt[A, Stream[M]])
    extends MsgT[A, M]
case class Process[A, M](m: (ProcId, M), stream: Stream[M]) extends MsgT[A, M]
case class UpdateIdSet[A, M](f: Set[Int] => Set[Int]) extends MsgT[A, M]
case class UpdateRef[A, M](
    f: Map[ProcId, ActorRef[MsgT[A, M]]] => Map[ProcId, ActorRef[MsgT[A, M]]]
) extends MsgT[A, M]
case class Deleagte[A, M](
    procIds: ProcId,
    defaultStream: Stream[M],
    handle: HandleM[A, M, Unit]
) extends MsgT[A, M]
