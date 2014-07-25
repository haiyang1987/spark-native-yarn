package org.apache.spark

import java.io.ObjectInput
import java.io.ObjectOutput
import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler.ShuffleMapTask
import org.apache.spark.scheduler.TaskLocation
import breeze.linalg.split
import org.apache.spark.scheduler.ResultTask

class TezShuffleTask(
  stageId: Int,
  var _rdd: RDD[_],
  var _dep: ShuffleDependency[_, _, _],
  _partitionId: Int,
  @transient private var locs: Seq[TaskLocation]) extends ShuffleMapTask(stageId, _rdd, _dep, _partitionId, locs) {

    protected def this() = this(0, null, null, 0, null)
    
    override def writeExternal(out: ObjectOutput) {
      out.writeObject(this.stageId)
      out.writeObject(this.rdd)
      out.writeObject(this.dep)
      out.writeInt(partitionId)
      out.writeLong(epoch)
      out.writeObject(split)
    }
    override def readExternal(in: ObjectInput) {
      val stageId = in.readObject
      this.rdd = in.readObject.asInstanceOf[RDD[_]]
      this.dep = in.readObject.asInstanceOf[ShuffleDependency[_, _, _]]
      this.partitionId = in.readInt
      this.epoch = in.readLong
      this.split = in.readObject().asInstanceOf[Partition]
    }
}

class TezResultTask[T, U](
  stageId: Int,
  var _rdd: RDD[T],
  var _func: (TaskContext, Iterator[T]) => U,
  _partitionId: Int,
  @transient locs: Seq[TaskLocation],
  var _outputId: Int)
  extends ResultTask(stageId, _rdd, _func, _partitionId, locs, _outputId) {

  def this() = this(0, null, null, 0, null, 0)
  override def writeExternal(out: ObjectOutput) {
    out.writeObject(this.stageId)
    out.writeObject(this.rdd)
    out.writeObject(this.func)
    out.writeInt(this.partitionId)
    out.writeInt(outputId)
    out.writeLong(epoch)
    out.writeObject(split)
  }
  override def readExternal(in: ObjectInput) {
    val stageId = in.readObject
    this.rdd = in.readObject.asInstanceOf[RDD[T]]
    this.func = in.readObject.asInstanceOf[(TaskContext, Iterator[T]) => U]
    this.partitionId = in.readInt
    this.outputId = in.readInt
    this.epoch = in.readLong
    this.split = in.readObject().asInstanceOf[Partition]
  }
}