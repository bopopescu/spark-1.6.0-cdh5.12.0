/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import scala.collection.mutable.HashMap

import org.mockito.Matchers._
import org.mockito.Mockito.{atLeast, never, spy, times, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mock.MockitoSugar

import org.apache.spark._
import org.apache.spark.storage.BlockManagerId

class FakeSchedulerBackend extends SchedulerBackend {
  def start() {}
  def stop() {}
  def reviveOffers() {}
  def defaultParallelism(): Int = 1
}

class TaskSchedulerImplSuite extends SparkFunSuite with LocalSparkContext with BeforeAndAfterEach
    with Logging with MockitoSugar {

  var failedTaskSetException: Option[Throwable] = None
  var failedTaskSetReason: String = null
  var failedTaskSet = false

  var taskScheduler: TaskSchedulerImpl = null
  var dagScheduler: DAGScheduler = null

  val stageToMockTaskSetBlacklist = new HashMap[Int, TaskSetBlacklist]()
  val stageToMockTaskSetManager = new HashMap[Int, TaskSetManager]()

  override def beforeEach(): Unit = {
    super.beforeEach()
    failedTaskSet = false
    failedTaskSetException = None
    failedTaskSetReason = null
    stageToMockTaskSetBlacklist.clear()
    stageToMockTaskSetManager.clear()
  }

  override def afterEach(): Unit = {
    super.afterEach()
    if (taskScheduler != null) {
      taskScheduler.stop()
      taskScheduler = null
    }
    if (dagScheduler != null) {
      dagScheduler.stop()
      dagScheduler = null
    }
  }

  def setupScheduler(confs: (String, String)*): TaskSchedulerImpl = {
    val conf = new SparkConf().setMaster("local").setAppName("TaskSchedulerImplSuite")
    confs.foreach { case (k, v) =>
      conf.set(k, v)
    }
    sc = new SparkContext(conf)
    taskScheduler = new TaskSchedulerImpl(sc)
    setupHelper()
  }

  def setupScheduler(blacklist: BlacklistTracker, confs: (String, String)*): TaskSchedulerImpl = {
    val conf = new SparkConf().setMaster("local").setAppName("TaskSchedulerImplSuite")
    confs.foreach { case (k, v) =>
      conf.set(k, v)
    }
    sc = new SparkContext(conf)
    taskScheduler =
      new TaskSchedulerImpl(sc, sc.conf.getInt("spark.task.maxFailures", 4), Some(blacklist))
    setupHelper()
  }

  def setupSchedulerWithMockTsm(blacklist: BlacklistTracker): TaskSchedulerImpl = {
    val conf = new SparkConf().setMaster("local").setAppName("TaskSchedulerImplSuite")
    conf.set(BlacklistConfs.BLACKLIST_ENABLED, true.toString)
    sc = new SparkContext(conf)
    taskScheduler =
      new TaskSchedulerImpl(sc, sc.conf.getInt("spark.task.maxFailures", 4), Some(blacklist)) {
        override def createTaskSetManager(taskSet: TaskSet, maxFailures: Int): TaskSetManager = {
          val tsm = super.createTaskSetManager(taskSet, maxFailures)
          val tsmSpy = spy(tsm)
          val taskSetBlacklist = mock[TaskSetBlacklist]
          when(tsmSpy.taskSetBlacklistHelperOpt).thenReturn(Some(taskSetBlacklist))
          stageToMockTaskSetManager(taskSet.stageId) = tsmSpy
          stageToMockTaskSetBlacklist(taskSet.stageId) = taskSetBlacklist

          // intentionally bogus, just lets us easily verify
          val execToFailures = new HashMap[String, ExecutorFailuresInTaskSet]()
          execToFailures(taskSet.stageId.toString) = new ExecutorFailuresInTaskSet("dummy")
          when(taskSetBlacklist.execToFailures).thenReturn(execToFailures)
          tsmSpy
        }
      }
    setupHelper()
  }

  def setupHelper(): TaskSchedulerImpl = {
    taskScheduler.initialize(new FakeSchedulerBackend)
    // Need to initialize a DAGScheduler for the taskScheduler to use for callbacks.
    dagScheduler = new DAGScheduler(sc, taskScheduler) {
      override def taskStarted(task: Task[_], taskInfo: TaskInfo): Unit = {}
      override def executorAdded(execId: String, host: String): Unit = {}
      override def taskSetFailed(
          taskSet: TaskSet,
          reason: String,
          exception: Option[Throwable]): Unit = {
        // Normally the DAGScheduler puts this in the event loop, which will eventually fail
        // dependent jobs
        failedTaskSet = true
        failedTaskSetReason = reason
        failedTaskSetException = exception
      }
    }
    taskScheduler
  }

  test("Scheduler does not always schedule tasks on the same workers") {
    val taskScheduler = setupScheduler()
    val numFreeCores = 1
    val workerOffers = IndexedSeq(new WorkerOffer("executor0", "host0", numFreeCores),
      new WorkerOffer("executor1", "host1", numFreeCores))
    // Repeatedly try to schedule a 1-task job, and make sure that it doesn't always
    // get scheduled on the same executor. While there is a chance this test will fail
    // because the task randomly gets placed on the first executor all 1000 times, the
    // probability of that happening is 2^-1000 (so sufficiently small to be considered
    // negligible).
    val numTrials = 1000
    val selectedExecutorIds = 1.to(numTrials).map { _ =>
      val taskSet = FakeTask.createTaskSet(1)
      taskScheduler.submitTasks(taskSet)
      val taskDescriptions = taskScheduler.resourceOffers(workerOffers).flatten
      assert(1 === taskDescriptions.length)
      taskDescriptions(0).executorId
    }
    val count = selectedExecutorIds.count(_ == workerOffers(0).executorId)
    assert(count > 0)
    assert(count < numTrials)
    assert(!failedTaskSet)
  }

  test("Scheduler correctly accounts for multiple CPUs per task") {
    val taskCpus = 2
    val taskScheduler = setupScheduler("spark.task.cpus" -> taskCpus.toString)
    // Give zero core offers. Should not generate any tasks
    val zeroCoreWorkerOffers = IndexedSeq(new WorkerOffer("executor0", "host0", 0),
      new WorkerOffer("executor1", "host1", 0))
    val taskSet = FakeTask.createTaskSet(1)
    taskScheduler.submitTasks(taskSet)
    var taskDescriptions = taskScheduler.resourceOffers(zeroCoreWorkerOffers).flatten
    assert(0 === taskDescriptions.length)

    // No tasks should run as we only have 1 core free.
    val numFreeCores = 1
    val singleCoreWorkerOffers = IndexedSeq(new WorkerOffer("executor0", "host0", numFreeCores),
      new WorkerOffer("executor1", "host1", numFreeCores))
    taskScheduler.submitTasks(taskSet)
    taskDescriptions = taskScheduler.resourceOffers(singleCoreWorkerOffers).flatten
    assert(0 === taskDescriptions.length)

    // Now change the offers to have 2 cores in one executor and verify if it
    // is chosen.
    val multiCoreWorkerOffers = IndexedSeq(new WorkerOffer("executor0", "host0", taskCpus),
      new WorkerOffer("executor1", "host1", numFreeCores))
    taskScheduler.submitTasks(taskSet)
    taskDescriptions = taskScheduler.resourceOffers(multiCoreWorkerOffers).flatten
    assert(1 === taskDescriptions.length)
    assert("executor0" === taskDescriptions(0).executorId)
    assert(!failedTaskSet)
  }

  test("Scheduler does not crash when tasks are not serializable") {
    val taskCpus = 2
    val taskScheduler = setupScheduler("spark.task.cpus" -> taskCpus.toString)
    val numFreeCores = 1
    val taskSet = new TaskSet(
      Array(new NotSerializableFakeTask(1, 0), new NotSerializableFakeTask(0, 1)), 0, 0, 0, null)
    val multiCoreWorkerOffers = IndexedSeq(new WorkerOffer("executor0", "host0", taskCpus),
      new WorkerOffer("executor1", "host1", numFreeCores))
    taskScheduler.submitTasks(taskSet)
    var taskDescriptions = taskScheduler.resourceOffers(multiCoreWorkerOffers).flatten
    assert(0 === taskDescriptions.length)
    assert(failedTaskSet)
    assert(failedTaskSetReason.contains("Failed to serialize task"))

    // Now check that we can still submit tasks
    // Even if one of the task sets has not-serializable tasks, the other task set should
    // still be processed without error
    taskScheduler.submitTasks(FakeTask.createTaskSet(1))
    taskScheduler.submitTasks(taskSet)
    taskDescriptions = taskScheduler.resourceOffers(multiCoreWorkerOffers).flatten
    assert(taskDescriptions.map(_.executorId) === Seq("executor0"))
  }

  test("refuse to schedule concurrent attempts for the same stage (SPARK-8103)") {
    val taskScheduler = setupScheduler()
    val attempt1 = FakeTask.createTaskSet(1, 0)
    val attempt2 = FakeTask.createTaskSet(1, 1)
    taskScheduler.submitTasks(attempt1)
    intercept[IllegalStateException] { taskScheduler.submitTasks(attempt2) }

    // OK to submit multiple if previous attempts are all zombie
    taskScheduler.taskSetManagerForAttempt(attempt1.stageId, attempt1.stageAttemptId)
      .get.isZombie = true
    taskScheduler.submitTasks(attempt2)
    val attempt3 = FakeTask.createTaskSet(1, 2)
    intercept[IllegalStateException] { taskScheduler.submitTasks(attempt3) }
    taskScheduler.taskSetManagerForAttempt(attempt2.stageId, attempt2.stageAttemptId)
      .get.isZombie = true
    taskScheduler.submitTasks(attempt3)
    assert(!failedTaskSet)
  }

  test("don't schedule more tasks after a taskset is zombie") {
    val taskScheduler = setupScheduler()

    val numFreeCores = 1
    val workerOffers = IndexedSeq(new WorkerOffer("executor0", "host0", numFreeCores))
    val attempt1 = FakeTask.createTaskSet(10)

    // submit attempt 1, offer some resources, some tasks get scheduled
    taskScheduler.submitTasks(attempt1)
    val taskDescriptions = taskScheduler.resourceOffers(workerOffers).flatten
    assert(1 === taskDescriptions.length)

    // now mark attempt 1 as a zombie
    taskScheduler.taskSetManagerForAttempt(attempt1.stageId, attempt1.stageAttemptId)
      .get.isZombie = true

    // don't schedule anything on another resource offer
    val taskDescriptions2 = taskScheduler.resourceOffers(workerOffers).flatten
    assert(0 === taskDescriptions2.length)

    // if we schedule another attempt for the same stage, it should get scheduled
    val attempt2 = FakeTask.createTaskSet(10, 1)

    // submit attempt 2, offer some resources, some tasks get scheduled
    taskScheduler.submitTasks(attempt2)
    val taskDescriptions3 = taskScheduler.resourceOffers(workerOffers).flatten
    assert(1 === taskDescriptions3.length)
    val mgr = taskScheduler.taskIdToTaskSetManager.get(taskDescriptions3(0).taskId).get
    assert(mgr.taskSet.stageAttemptId === 1)
    assert(!failedTaskSet)
  }

  test("if a zombie attempt finishes, continue scheduling tasks for non-zombie attempts") {
    val taskScheduler = setupScheduler()

    val numFreeCores = 10
    val workerOffers = IndexedSeq(new WorkerOffer("executor0", "host0", numFreeCores))
    val attempt1 = FakeTask.createTaskSet(10)

    // submit attempt 1, offer some resources, some tasks get scheduled
    taskScheduler.submitTasks(attempt1)
    val taskDescriptions = taskScheduler.resourceOffers(workerOffers).flatten
    assert(10 === taskDescriptions.length)

    // now mark attempt 1 as a zombie
    val mgr1 = taskScheduler.taskSetManagerForAttempt(attempt1.stageId, attempt1.stageAttemptId).get
    mgr1.isZombie = true

    // don't schedule anything on another resource offer
    val taskDescriptions2 = taskScheduler.resourceOffers(workerOffers).flatten
    assert(0 === taskDescriptions2.length)

    // submit attempt 2
    val attempt2 = FakeTask.createTaskSet(10, 1)
    taskScheduler.submitTasks(attempt2)

    // attempt 1 finished (this can happen even if it was marked zombie earlier -- all tasks were
    // already submitted, and then they finish)
    taskScheduler.taskSetFinished(mgr1)

    // now with another resource offer, we should still schedule all the tasks in attempt2
    val taskDescriptions3 = taskScheduler.resourceOffers(workerOffers).flatten
    assert(10 === taskDescriptions3.length)

    taskDescriptions3.foreach { task =>
      val mgr = taskScheduler.taskIdToTaskSetManager.get(task.taskId).get
      assert(mgr.taskSet.stageAttemptId === 1)
    }
    assert(!failedTaskSet)
  }

  test("tasks are not re-scheduled while executor loss reason is pending") {
    val taskScheduler = setupScheduler()

    val e0Offers = IndexedSeq(new WorkerOffer("executor0", "host0", 1))
    val e1Offers = IndexedSeq(new WorkerOffer("executor1", "host0", 1))
    val attempt1 = FakeTask.createTaskSet(1)

    // submit attempt 1, offer resources, task gets scheduled
    taskScheduler.submitTasks(attempt1)
    val taskDescriptions = taskScheduler.resourceOffers(e0Offers).flatten
    assert(1 === taskDescriptions.length)

    // mark executor0 as dead but pending fail reason
    taskScheduler.executorLost("executor0", LossReasonPending)

    // offer some more resources on a different executor, nothing should change
    val taskDescriptions2 = taskScheduler.resourceOffers(e1Offers).flatten
    assert(0 === taskDescriptions2.length)

    // provide the actual loss reason for executor0
    taskScheduler.executorLost("executor0", SlaveLost("oops"))

    // executor0's tasks should have failed now that the loss reason is known, so offering more
    // resources should make them be scheduled on the new executor.
    val taskDescriptions3 = taskScheduler.resourceOffers(e1Offers).flatten
    assert(1 === taskDescriptions3.length)
    assert("executor1" === taskDescriptions3(0).executorId)
    assert(!failedTaskSet)
  }

  test("scheduled tasks obey task and stage blacklists") {
    val blacklist = mock[BlacklistTracker]
    taskScheduler = setupSchedulerWithMockTsm(blacklist)
    (0 to 2).foreach { stageId =>
      val taskSet = FakeTask.createTaskSet(numTasks = 2, stageId = stageId, stageAttemptId = 0)
      taskScheduler.submitTasks(taskSet)
    }

    val offers = IndexedSeq(
      new WorkerOffer("executor0", "host0", 1),
      new WorkerOffer("executor1", "host1", 1),
      new WorkerOffer("executor2", "host1", 1),
      new WorkerOffer("executor3", "host2", 10)
    )

    // setup our mock blacklist:
    // stage 0 is blacklisted on node "host1"
    // stage 1 is blacklisted on executor "executor3"
    // stage 0, part 0 is blacklisted on executor 0
    // (later stubs take precedence over earlier ones)
    when(blacklist.isNodeBlacklisted(anyString())).thenReturn(false)
    when(blacklist.isExecutorBlacklisted(anyString())).thenReturn(false)
    // setup some defaults, then override them with particulars
    stageToMockTaskSetBlacklist.values.foreach { taskSetBlacklist =>
      when(taskSetBlacklist.isNodeBlacklistedForTaskSet(anyString())).thenReturn(false)
      when(taskSetBlacklist.isExecutorBlacklistedForTaskSet(anyString())).thenReturn(false)
      when(taskSetBlacklist.isExecutorBlacklistedForTask(anyString(), anyInt())).thenReturn(false)
      when(taskSetBlacklist.isNodeBlacklistedForTask(anyString(), anyInt())).thenReturn(false)
    }
    when(stageToMockTaskSetBlacklist(0).isNodeBlacklistedForTaskSet("host1")).thenReturn(true)
    when(stageToMockTaskSetBlacklist(1).isExecutorBlacklistedForTaskSet("executor3"))
      .thenReturn(true)
    when(stageToMockTaskSetBlacklist(0).isExecutorBlacklistedForTask("executor0", 0))
      .thenReturn(true)

    val firstTaskAttempts = taskScheduler.resourceOffers(offers).flatten
    // these verifications are tricky b/c (a) we reference them multiple times -- also invoked when
    // we check if we need to abort any stages from unschedulability and (b) resources
    // are offered to the taskSets until they have no more free cores, so we don't necessarily
    // even make an offer for each resource to each taskSet.
    ('0' until '2').foreach { hostNum =>
      verify(blacklist, atLeast(1)).isNodeBlacklisted(s"host$hostNum")
    }
    (0 to 2).foreach { stageId =>
      verify(stageToMockTaskSetBlacklist(stageId), atLeast(1))
        .isNodeBlacklistedForTaskSet(anyString())
    }
    for {
      exec <- Seq("executor1", "executor2")
      part <- 0 to 1
    } {
      // the node blacklist should ensure we never check the task blacklist.  This is important
      // for performance, otherwise we end up changing an O(1) operation into a
      // O(numPendingTasks) one
      verify(stageToMockTaskSetBlacklist(0), never).isExecutorBlacklistedForTask(exec, part)
    }

    // similarly, the executor blacklist for an entire stage should prevent us from ever checking
    // the blacklist for specific parts in a stage.
    (0 to 1).foreach { part =>
      verify(stageToMockTaskSetBlacklist(1), never).isExecutorBlacklistedForTask("executor3", part)
    }

    // we should schedule all tasks.
    assert(firstTaskAttempts.size === 6)
    def tasksForStage(stageId: Int): Seq[TaskDescription] = {
      firstTaskAttempts.filter{_.name.contains(s"stage $stageId")}
    }
    tasksForStage(0).foreach { task =>
      // exec 1 & 2 blacklisted for node
      // exec 0 blacklisted just for part 0
      if (task.index == 0) {
        assert(task.executorId === "executor3")
      } else {
        assert(Set("executor0", "executor3").contains(task.executorId))
      }
    }
    tasksForStage(1).foreach { task =>
      // exec 3 blacklisted
      assert("executor3" != task.executorId)
    }
    // no restrictions on stage 2

    // have all tasksets finish (stages 0 & 1 successfully, 2 unsuccessfully)
    (0 to 2).foreach { stageId =>
      val tasks = tasksForStage(stageId)
      val tsm = taskScheduler.taskSetManagerForAttempt(stageId, 0).get
      val valueSer = SparkEnv.get.serializer.newInstance()
      if (stageId == 2) {
        // just need to make one task fail 4 times
        var task = tasks(0)
        val taskIndex = task.index
        (0 until 4).foreach { attempt =>
          assert(task.attemptNumber === attempt)
          tsm.handleFailedTask(task.taskId, TaskState.FAILED, TaskResultLost)
          val nextAttempts =
            taskScheduler.resourceOffers(IndexedSeq(WorkerOffer("executor4", "host4", 1))).flatten
          if (attempt < 3) {
            assert(nextAttempts.size === 1)
            task = nextAttempts(0)
            assert(task.index === taskIndex)
          } else {
            assert(nextAttempts.size === 0)
          }
        }

        // end the other task of the taskset, doesn't matter whether it succeeds or fails
        val otherTask = tasks(1)
        val result = new DirectTaskResult[Int](valueSer.serialize(otherTask.taskId), Map(), null)
        tsm.handleSuccessfulTask(otherTask.taskId, result)
      } else {
        tasks.foreach { task =>
          val result = new DirectTaskResult[Int](valueSer.serialize(task.taskId), Map(), null)
          tsm.handleSuccessfulTask(task.taskId, result)
        }
      }
    }

    // the tasksSets complete, so the tracker should be notified
    verify(blacklist, times(1)).updateBlacklistForSuccessfulTaskSet(
      0, 0, stageToMockTaskSetBlacklist(0).execToFailures)
    verify(blacklist, times(1)).updateBlacklistForSuccessfulTaskSet(
      1, 0, stageToMockTaskSetBlacklist(1).execToFailures)
  }

  test("scheduled tasks obey node and executor blacklists") {
    val blacklist = mock[BlacklistTracker]
    taskScheduler = setupSchedulerWithMockTsm(blacklist)
    (0 to 2).foreach { stageId =>
      val taskSet = FakeTask.createTaskSet(numTasks = 2, stageId = stageId, stageAttemptId = 0)
      taskScheduler.submitTasks(taskSet)
    }

    val offers = IndexedSeq(
      new WorkerOffer("executor0", "host0", 1),
      new WorkerOffer("executor1", "host1", 1),
      new WorkerOffer("executor2", "host1", 1),
      new WorkerOffer("executor3", "host2", 10)
    )

    // setup our mock blacklist:
    // host1, executor0 & executor3 are completely blacklisted (which covers all the executors)
    when(blacklist.isNodeBlacklisted(anyString())).thenReturn(false)
    when(blacklist.isNodeBlacklisted("host1")).thenReturn(true)
    when(blacklist.isExecutorBlacklisted(anyString())).thenReturn(false)
    when(blacklist.isExecutorBlacklisted("executor0")).thenReturn(true)
    when(blacklist.isExecutorBlacklisted("executor3")).thenReturn(true)

    val stageToTsm = (0 to 2).map { stageId =>
      val tsm = taskScheduler.taskSetManagerForAttempt(stageId, 0).get
      stageId -> tsm
    }.toMap

    val firstTaskAttempts = taskScheduler.resourceOffers(offers).flatten
    firstTaskAttempts.foreach { task => logInfo(s"scheduled $task on ${task.executorId}") }
    assert(firstTaskAttempts.isEmpty)
    ('0' until '2').foreach { hostNum =>
      verify(blacklist, atLeast(1)).isNodeBlacklisted("host" + hostNum)
    }

    // we should have aborted the existing stages, since they aren't schedulable
    (0 to 2).foreach { stageId =>
      assert(stageToTsm(stageId).isZombie)
    }
  }

  test("abort stage when all executors are blacklisted") {
    val blacklist = mock[BlacklistTracker]
    taskScheduler = setupSchedulerWithMockTsm(blacklist)
    val taskSet = FakeTask.createTaskSet(numTasks = 10, stageAttemptId = 0)
    taskScheduler.submitTasks(taskSet)
    val tsm = stageToMockTaskSetManager(0)

    // first just submit some offers so the scheduler knows about all the executors
    taskScheduler.resourceOffers(IndexedSeq(
      WorkerOffer("executor0", "host0", 2),
      WorkerOffer("executor1", "host0", 2),
      WorkerOffer("executor2", "host0", 2),
      WorkerOffer("executor3", "host1", 2)
    ))

    // now say our blacklist updates to blacklist a bunch of resources, but *not* everything
    when(blacklist.isNodeBlacklisted(anyString())).thenReturn(false)
    when(blacklist.isNodeBlacklisted("host1")).thenReturn(true)
    when(blacklist.isExecutorBlacklisted(anyString())).thenReturn(false)
    when(blacklist.isExecutorBlacklisted("executor0")).thenReturn(true)

    // make an offer on the blacklisted resources.  We won't schedule anything, but also won't
    // abort yet, since we know of other resources that work
    assert(taskScheduler.resourceOffers(IndexedSeq(
      WorkerOffer("executor0", "host0", 2),
      WorkerOffer("executor3", "host1", 2)
    )).flatten.size === 0)
    assert(!tsm.isZombie)

    // now update the blacklist so that everything really is blacklisted
    when(blacklist.isExecutorBlacklisted("executor1")).thenReturn(true)
    when(blacklist.isExecutorBlacklisted("executor2")).thenReturn(true)
    assert(taskScheduler.resourceOffers(IndexedSeq(
      WorkerOffer("executor0", "host0", 2),
      WorkerOffer("executor3", "host1", 2)
    )).flatten.size === 0)
    assert(tsm.isZombie)
    verify(tsm).abort(anyString(), anyObject())
  }

  test("abort stage if executor loss results in unschedulability from previously failed tasks") {
    // Make sure we can detect when a taskset becomes unschedulable from a blacklisting.  This
    // test explores a particular corner case -- you may have one task fail, but still be
    // schedulable on another executor.  However, that executor may fail later on, leaving the
    // first task with no place to run.
    val taskScheduler = setupScheduler(
      BlacklistConfs.BLACKLIST_ENABLED -> "true"
    )

    val taskSet = FakeTask.createTaskSet(2)
    taskScheduler.submitTasks(taskSet)
    val tsm = taskScheduler.taskSetManagerForAttempt(taskSet.stageId, taskSet.stageAttemptId).get

    val firstTaskAttempts = taskScheduler.resourceOffers(IndexedSeq(
      new WorkerOffer("executor0", "host0", 1),
      new WorkerOffer("executor1", "host1", 1)
    )).flatten
    assert(Set("executor0", "executor1") === firstTaskAttempts.map(_.executorId).toSet)

    // Fail one of the tasks, but leave the other running.
    val failedTask = firstTaskAttempts.find(_.executorId == "executor0").get
    taskScheduler.handleFailedTask(tsm, failedTask.taskId, TaskState.FAILED, TaskResultLost)
    // At this point, our failed task could run on the other executor, so don't give up the task
    // set yet.
    assert(!failedTaskSet)

    // Now we fail our second executor.  The other task can still run on executor1, so make an offer
    // on that executor, and make sure that the other task (not the failed one) is assigned there.
    taskScheduler.executorLost("executor1", SlaveLost("oops"))
    val nextTaskAttempts =
      taskScheduler.resourceOffers(IndexedSeq(new WorkerOffer("executor0", "host0", 1))).flatten
    // Note: Its OK if some future change makes this already realize the taskset has become
    // unschedulable at this point (though in the current implementation, we're sure it will not).
    assert(nextTaskAttempts.size === 1)
    assert(nextTaskAttempts.head.executorId === "executor0")
    assert(nextTaskAttempts.head.attemptNumber === 1)
    assert(nextTaskAttempts.head.index != failedTask.index)

    // Now we should definitely realize that our task set is unschedulable, because the only
    // task left can't be scheduled on any executors due to the blacklist.
    taskScheduler.resourceOffers(IndexedSeq(new WorkerOffer("executor0", "host0", 1)))
    sc.listenerBus.waitUntilEmpty(100000)
    assert(tsm.isZombie)
    assert(failedTaskSet)
    val idx = failedTask.index
    assert(failedTaskSetReason === s"Aborting TaskSet 0.0 because task $idx (partition $idx) " +
      s"cannot run anywhere due to node and executor blacklist.  Blacklisting behavior can be " +
      s"configured via spark.blacklist.*.")
  }

  test("don't abort if there is an executor available, though it hasn't had scheduled tasks yet") {
    // interaction of SPARK-15865 & SPARK-16106
    // if we have a small number of tasks, we might be able to schedule them all on the first
    // executor.  But if those tasks fail, we should still realize there is another executor
    // available and not bail on the job

    val taskScheduler = setupScheduler(
      BlacklistConfs.BLACKLIST_ENABLED -> "true"
    )

    val taskSet = FakeTask.createTaskSet(2, (0 until 2).map { _ => Seq(TaskLocation("host0")) }: _*)
    taskScheduler.submitTasks(taskSet)
    val tsm = taskScheduler.taskSetManagerForAttempt(taskSet.stageId, taskSet.stageAttemptId).get

    val offers = IndexedSeq(
      // each offer has more than enough free cores for the entire task set, so when combined
      // with the locality preferences, we schedule all tasks on one executor
      new WorkerOffer("executor0", "host0", 4),
      new WorkerOffer("executor1", "host1", 4)
    )
    val firstTaskAttempts = taskScheduler.resourceOffers(offers).flatten
    assert(firstTaskAttempts.size == 2)
    firstTaskAttempts.foreach { taskAttempt => assert("executor0" === taskAttempt.executorId) }

    // fail all the tasks on the bad executor
    firstTaskAttempts.foreach { taskAttempt =>
      taskScheduler.handleFailedTask(tsm, taskAttempt.taskId, TaskState.FAILED, TaskResultLost)
    }

    // Here is the main check of this test -- we have the same offers again, and we schedule it
    // successfully.  Because the scheduler first tries to schedule with locality in mind, at first
    // it won't schedule anything on executor1.  But despite that, we don't abort the job.  Then the
    // scheduler tries for ANY locality, and successfully schedules tasks on executor1.
    val secondTaskAttempts = taskScheduler.resourceOffers(offers).flatten
    assert(secondTaskAttempts.size == 2)
    secondTaskAttempts.foreach { taskAttempt => assert("executor1" === taskAttempt.executorId) }
    assert(!failedTaskSet)
  }

  test("SPARK-16106 locality levels updated if executor added to existing host") {
    val taskScheduler = setupScheduler()

    taskScheduler.submitTasks(FakeTask.createTaskSet(2, 0,
      (0 until 2).map { _ => Seq(TaskLocation("host0", "executor2")) }: _*
    ))

    val taskDescs = taskScheduler.resourceOffers(IndexedSeq(
      new WorkerOffer("executor0", "host0", 1),
      new WorkerOffer("executor1", "host1", 1)
    )).flatten
    // only schedule one task because of locality
    assert(taskDescs.size === 1)

    val mgr = taskScheduler.taskIdToTaskSetManager.get(taskDescs(0).taskId).get
    assert(mgr.myLocalityLevels.toSet === Set(TaskLocality.NODE_LOCAL, TaskLocality.ANY))
    // we should know about both executors, even though we only scheduled tasks on one of them
    assert(taskScheduler.getExecutorsAliveOnHost("host0") === Some(Set("executor0")))
    assert(taskScheduler.getExecutorsAliveOnHost("host1") === Some(Set("executor1")))

    // when executor2 is added, we should realize that we can run process-local tasks.
    // And we should know its alive on the host.
    val secondTaskDescs = taskScheduler.resourceOffers(
      IndexedSeq(new WorkerOffer("executor2", "host0", 1))).flatten
    assert(secondTaskDescs.size === 1)
    assert(mgr.myLocalityLevels.toSet ===
      Set(TaskLocality.PROCESS_LOCAL, TaskLocality.NODE_LOCAL, TaskLocality.ANY))
    assert(taskScheduler.getExecutorsAliveOnHost("host0") === Some(Set("executor0", "executor2")))
    assert(taskScheduler.getExecutorsAliveOnHost("host1") === Some(Set("executor1")))

    // And even if we don't have anything left to schedule, another resource offer on yet another
    // executor should also update the set of live executors
    val thirdTaskDescs = taskScheduler.resourceOffers(
      IndexedSeq(new WorkerOffer("executor3", "host1", 1))).flatten
    assert(thirdTaskDescs.size === 0)
    assert(taskScheduler.getExecutorsAliveOnHost("host1") === Some(Set("executor1", "executor3")))
  }

  test("scheduler checks for executors that can be expired from blacklist") {
    val blacklist = mock[BlacklistTracker]
    taskScheduler = setupScheduler(blacklist)

    taskScheduler.submitTasks(FakeTask.createTaskSet(1, 0))
    taskScheduler.resourceOffers(IndexedSeq(
      new WorkerOffer("executor0", "host0", 1)
    )).flatten

    verify(blacklist).applyBlacklistTimeout()
  }

  test("don't update blacklist for shuffle-fetch failures, preemption, denied commits, " +
    "or killed tasks") {
    val blacklist = mock[BlacklistTracker]
    taskScheduler = setupSchedulerWithMockTsm(blacklist)
    val stage0 = FakeTask.createTaskSet(numTasks = 4, stageAttemptId = 0)
    taskScheduler.submitTasks(stage0)
    val taskDescs = taskScheduler.resourceOffers(
      IndexedSeq(new WorkerOffer("executor0", "host0", 10))).flatten
    assert(taskDescs.size === 4)

    val tsm = stageToMockTaskSetManager(0)
    taskScheduler.handleFailedTask(tsm, taskDescs(0).taskId, TaskState.FAILED,
      FetchFailed(BlockManagerId("executor1", "host1", 12345), 0, 0, 0, "ignored"))
    taskScheduler.handleFailedTask(tsm, taskDescs(1).taskId, TaskState.FAILED,
      ExecutorLostFailure("executor0", exitCausedByApp = false, reason = None))
    taskScheduler.handleFailedTask(tsm, taskDescs(2).taskId, TaskState.FAILED,
      TaskCommitDenied(0, 2, 0))
    taskScheduler.handleFailedTask(tsm, taskDescs(3).taskId, TaskState.KILLED,
      TaskKilled)
    verify(stageToMockTaskSetBlacklist(0), never())
      .updateBlacklistForFailedTask(anyString(), anyString(), anyInt())
  }
}
