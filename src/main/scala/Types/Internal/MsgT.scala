package Types.Internal

import Instances.*
import Types.HandleM
import org.apache.pekko.actor.typed.ActorRef

sealed trait MsgT[A, M]
case class Merge[A, M](nodeId: ProcId, ids: Set[ProcId], v: SharedWcrdt[A, LazyList[M]])
    extends MsgT[A, M]
case class Process[A, M](m: (ProcId, M), stream: LazyList[M]) extends MsgT[A, M]
case class SetIdSet[A, M](s: Set[Int]) extends MsgT[A, M]
case class SetRefs[A, M](
    s: Set[ActorRef[MsgT[A, M]]], shouldSendMerge: Option[ActorRef[MsgT[A, M]]]
) extends MsgT[A, M]
case class Delegate[A, M](
    procId: ProcId,
    knownRestartPoint: (WindowId, LazyList[M], A),
    handle: HandleM[A, M, Unit]
) extends MsgT[A, M]
case class transferReplica[A, M](
    initCRDT: A,
    procs: Map[ProcId, (HandleM[A, M, Unit], LazyList[M])],
    targetRef: ActorRef[MsgT[A, M]]
) extends MsgT[A, M]
