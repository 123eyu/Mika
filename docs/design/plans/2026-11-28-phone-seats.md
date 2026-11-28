# 手机座位 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 不同对话的任务随时能开、同时进行；只有「同时操作手机」按设备能力限量（座位），没座位时在第一次接手机那一步等，并写明在等哪个对话。

**Architecture:** 登记处 `TaskCenter` 的名额从「按设备能力」改成宽的常量总数；新增 `PhoneSeats`（登记处持有）管座位的持有与先来先等，任务在 `connectPhone` 第一步取座位、收尾时随 `onEnded` 释放。回锁只看座位上的和等座位的任务。聊天页、服务里所有 `parallel` 分支一律按并行走，删除串行路径和 `parallelTasks` 开关。

**Tech Stack:** Kotlin、kotlinx.coroutines（`CompletableDeferred`）、Jetpack Compose、JUnit4（`app/src/test` 本地单测）。

**Spec:** `docs/design/specs/2026-11-28-phone-seats-design.md`

## Global Constraints

- 总数上限：一般设备 `4`，低内存设备 `2`（`ActivityManager.isLowRamDevice`）。
- 座位数 = `ParallelCapability.detect(sdk, lowRam, userMax)`：`sdk < 34` → 1；低内存 → 1；否则 `userMax.coerceIn(1, 3)`，默认 `2`。不再有 `secondDisplayFailed` 参数。
- 同一对话一次一件、同一工作区互斥（`sameWorkspaceActive`）不动。
- 座位一直留到任务收尾（等用户回答、等接手时不放）。
- 等座位不设超时，先等先得，可被取消、暂停打断。
- 等座位的文案：`等「%1$s」用完手机`；取不到对话标题时 `等另一个任务用完手机`。英文 `Waiting for “%1$s” to finish with the phone` / `Waiting for another task to finish with the phone`。
- 设置项改名：「同时操作手机的任务」/ `Phone tasks at once`，副标题「操作 App 的任务各用一块虚拟屏；只聊天、查网页的任务不占」/ `Tasks that operate apps each use a virtual screen; chat and web-only tasks don't count`。所有版本都显示。
- 所有新增、改动的字符串在 `res/values*/` 下已有该文件的 13 个语言里都要有（照 `strings_pause.xml` 的做法）。
- 代码注释、提交信息用中文；提交信息格式 `feat(task): 问题 → 改法 (#839)`。
- 新代码放进 `task/` 已有包，不新开顶层包。
- 分支：从 `main` 新开 `feat/phone-seats`；本计划和设计文档随第一个提交进去。

## Review Focus

1. 等座位的任务被取消（点停止、微信「停止」）→ 它从等待队列里移出，后面的照常拿到；不能把座位交给已取消的等待者导致座位泄漏。（Task 1 测试 `cancelledWaiterDoesNotSwallowSeat`）
2. 同一任务第一次接手机失败（`PhoneUnavailable`，比如助手没连）后下一步重试 `connectPhone` → 已持有座位时立即返回，不能自己等自己卡死。（Task 1 测试 `holderTakingAgainReturnsImmediately`）
3. 手机任务解锁并收尾，同时另一个只聊天的任务还在跑 → 照常回锁。（Task 2 测试 `phoneUsersIgnoreChatOnlyRuns`）
4. 用户在设置里把座位从 2 调到 1，此刻两个任务都占着 → 不抢座位，新来的等到持有数降到 1 以下才拿到。（Task 1 测试 `shrinkingSeatsDoesNotPreemptButGatesNewTakers`）
5. 占座位的对话没有标题（新对话第一句还没存标题）→ 用「等另一个任务用完手机」，不显示空引号。（Task 3 测试 `seatWaitCopyFallsBackWithoutTitle`）

---

### Task 1: `PhoneSeats` 座位分配

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/task/PhoneSeats.kt`
- Test: `app/src/test/java/com/wangzan101/mika/task/PhoneSeatsTest.kt`

**Interfaces:**
- Produces:
  - `class PhoneSeats(private val count: () -> Int)`
  - `suspend fun take(owner: String, onWait: (holders: List<String>) -> Unit)`：已持有直接返回；有空座立即占下；否则调一次 `onWait(当前持有者按占座先后)` 后挂起排队，轮到时返回。被取消时从队里移出（`finally` 里同一把锁内处理），若座位已交给它则转交下一位。
  - `fun release(owner: String)`：放掉座位（不持有也不报错），并按先来先等把空出的座位交给等待者，直到持有数达到 `count()`。
  - `fun holders(): List<String>`、`fun waiting(): List<String>`、`fun holds(owner: String): Boolean`
  - 所有查与改在 `synchronized(this)` 内；唤醒用每个等待者一个 `CompletableDeferred<Unit>`。

- [ ] **Step 1: 写失败的测试**（`runTest` + `launch`，用 `advanceUntilIdle()` 推进）

```kotlin
@Test fun takesFreeSeatImmediately()            // count=2: take("a"), take("b") 都不调 onWait;holders()==["a","b"]
@Test fun thirdWaitsUntilRelease()              // count=2: c 调 onWait(["a","b"]) 后挂起;release("a") 后 c 返回;holders()==["b","c"]
@Test fun waitersAreServedInOrder()             // count=1: b、c 依次等;release("a") → b 拿到、c 仍在 waiting()
@Test fun holderTakingAgainReturnsImmediately() // count=1: take("a") 两次,第二次不调 onWait
@Test fun cancelledWaiterDoesNotSwallowSeat()   // count=1: b、c 等;取消 b 的 job;release("a") → c 拿到;waiting() 为空
@Test fun releaseRacingCancelPassesSeatOn()     // count=1: b 等;release("a") 与 cancel(b) 同一调度轮里发生 → 最终 holders() 为空或 ["b"],且之后 take("c") 能拿到(无泄漏)
@Test fun shrinkingSeatsDoesNotPreemptButGatesNewTakers() // count 2→1 时 a、b 都占着;c 等;release("a") 后 c 仍等;release("b") 后 c 拿到
@Test fun releaseOfNonHolderIsNoop()
```

- [ ] **Step 2:** 运行 `./gradlew :app:testDebugUnitTest --tests '*PhoneSeatsTest'`，预期编译失败（`PhoneSeats` 不存在）。
- [ ] **Step 3:** 实现 `PhoneSeats.kt`（文件头注释说明：座位是「同时操作手机」的名额，见设计文档）。
- [ ] **Step 4:** 再跑同一命令，预期全部 PASS。
- [ ] **Step 5:** 提交（连同本计划和设计文档）：`feat(task): 加手机座位分配 PhoneSeats，先来先等、可取消 (#839)`

### Task 2: 登记处换成总数上限 + 座位，回锁只看用手机的任务

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRegistry.kt`（`TaskCenter`）
- Modify: `app/src/main/java/com/wangzan101/mika/task/ParallelCapability.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskQueueOps.kt:49-54`（`limit()`）
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskCenterState.kt:83-90`
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRelockOps.kt:33`
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskService.kt`（删 `parallel` 字段，`center` 构造）
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskLaunchOps.kt:79,129`（删两处 `parallel = …`，改掉「对话页先等服务空下来」一类过时注释）
- Test: `TaskRegistryTest.kt`、`ParallelCapabilityTest.kt`

**Interfaces:**
- Consumes: `PhoneSeats`（Task 1）
- Produces:
  - `TaskCenter` 构造新增参数 `seatCount: () -> Int = { 1 }`，属性 `val seats = PhoneSeats(seatCount)`。
  - `onEnded(run)` 里在 `resources.releaseOwner(keyOf(run))` 旁加 `seats.release(keyOf(run))`；排队中被取消的（`queued.removeAll` 分支）不涉及座位。
  - `@Synchronized fun phoneUsers(ignore: R? = null): List<R>`：`runs()` 里 key 在 `seats.holders()` 或 `seats.waiting()` 中、且不是 `ignore` 的。
  - `object TaskCapacity { const val TOTAL = 4; const val LOW_RAM = 2; fun max(lowRam: Boolean): Int }`，放在 `ParallelCapability.kt`。
  - `ParallelCapability.detect(sdk: Int, lowRam: Boolean, userMax: Int): ParallelLimit`（删第四个参数和 `SECOND_DISPLAY_FAILED`、字符串 `parallel_second_display_failed`）。
  - `TaskService.limit(): ParallelLimit = ParallelLimit(TaskCapacity.max(lowRam), null)`；新 `TaskService.seatLimit(): ParallelLimit` = `detect(...)` 并写 `taskState._parallelLimit`；`center` 传 `seatCount = { seatLimit().max }`。
  - `TaskCenterState` 删 `secondDisplayFailed` 与 `markSecondDisplayFailed()`；`_parallelLimit` 留着，含义改为座位数（KDoc 改掉）。
  - `TaskRelockOps.runsRemain()` 改为 `center.phoneUsers(ignore).isNotEmpty()`。

- [ ] **Step 1: 写/改失败的测试**
  - `TaskRegistryTest`：`overLimitQueues` 等已有名额测试保留（仍用 `limit`，含义是总数）；新增
    - `phoneUsersIgnoreChatOnlyRuns`：a、b 都 Started；`seats.take(a.key)`；`phoneUsers()` == `[a]`；`phoneUsers(ignore = a)` 为空。
    - `endingRunReleasesItsSeat`：seatCount=1，a 占座、b 等座（`launch`）；`onEnded(a)` 后 b 拿到座位。
  - `ParallelCapabilityTest`：删「第二块屏失败」用例，其余改三参数；新增 `TaskCapacity.max(false) == 4`、`max(true) == 2`。
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*TaskRegistryTest' --tests '*ParallelCapabilityTest'`，预期编译失败。
- [ ] **Step 3:** 按 Interfaces 改代码。`TaskRun` 构造仍收 `parallel` 参数到 Task 3 再删——此任务里 `TaskService.center` 传 `true`。
- [ ] **Step 4:** 同一命令 PASS；`./gradlew :app:compileDebugKotlin` 通过。
- [ ] **Step 5:** 提交：`feat(task): 任务总数改成宽的常量上限，手机座位记在登记处；回锁只等用手机的任务 (#839)`

### Task 3: 第一次接手机时取座位，等座位写明在等谁

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/TaskState.kt`（`GateReason` 加 `ref`）
- Modify: `app/src/main/java/com/wangzan101/mika/task/RunPhoneStage.kt`（`connectPhone` 开头取座位；建第二块屏失败的分支）
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`（删 `parallel` 构造参数及 KDoc）
- Modify: `app/src/main/java/com/wangzan101/mika/task/RunAgentStage.kt:246`、`TaskQueueOps.kt:30`（`parallel` 分支按并行走：恒刷新、恒用 `nextResultId()`；`ID_RESULT` 若再无引用就删）
- Create: `app/src/main/java/com/wangzan101/mika/task/SeatWaitCopy.kt`
- Modify: `app/src/main/res/values*/strings_task.xml`（加 `task_waiting_seat`、`task_waiting_seat_other`；删 `parallel_second_display_failed`）
- Test: `app/src/test/java/com/wangzan101/mika/task/SeatWaitCopyTest.kt`

**Interfaces:**
- Consumes: `TaskCenter.seats`（Task 2）
- Produces:
  - `GateReason(code, why, resumable, needsYou, val ref: String? = null)`：`ref` 是「点了跳到哪个对话」，目前只有等座位用。
  - `internal fun seatWaitCopy(title: String?): String`（`title.isNullOrBlank()` → `task_waiting_seat_other`，否则 `task_waiting_seat`）。
  - `connectPhone` 第一行（SDK 检查之后、`sharedShell.acquire()` 之前）：
    `service.center.seats.take(key) { holders -> publish(TaskState.Held(GateReason.queued(seatWaitCopy(title(holderConv))).copy(ref = holderConv))) }`，
    `holderConv` = `holders.first()` 对应任务的 `conversationId`（`service.center.byKey`），`title` 用 `ConversationStore.shared(service).title(id)`。取到后发布等之前的状态（照 `WAITING_DISPLAY` 那段的写法，取座前记下 `runState._state.value`）。
  - 建屏失败分支改为：`if (!service.otherRunHasDisplay(this)) throw e`，不再 `markSecondDisplayFailed()`，其余（`WAITING_DISPLAY` 等待后重建）不变。
  - `AndroidScreen(checkOtherTasks = true)`。

- [ ] **Step 1: 写失败的测试** `SeatWaitCopyTest`：`seatWaitCopyNamesHolder`（"买菜" → 含「买菜」）、`seatWaitCopyFallsBackWithoutTitle`（null 与 "  " → 等于 other 文案）。照仓库里其他 `tr()` 单测的做法拿字符串。
- [ ] **Step 2:** 跑 `--tests '*SeatWaitCopyTest'`，预期失败。
- [ ] **Step 3:** 按 Interfaces 实现；`./gradlew :core:test :app:compileDebugKotlin`。
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest --tests '*SeatWaitCopyTest' --tests '*TaskRegistryTest'` PASS。
- [ ] **Step 5:** 提交：`feat(task): 第一次接手机前取座位，没座位时停在「等「对话」用完手机」，第二块屏建失败不再永久退回一次一个 (#839)`

### Task 4: 点等座位的提示跳到占座的对话

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskNotifications.kt`（任务通知的点击意图）
- Modify: 聊天页显示任务状态行的那处（`chat/ui/` 下渲染 `Held` 状态行的组件，`grep -rn "GateReason\|\.why" app/src/main/java/com/wangzan101/mika/chat/ui` 找）
- Modify: 悬浮胶囊点击（`grep -rn "openApp(" app/src/main/java/com/wangzan101/mika` 找按对话打开的那处）

**Interfaces:**
- Consumes: `GateReason.ref`（Task 3）
- Produces: `internal fun TaskState.jumpTarget(own: String): String` = `(this as? TaskState.Held)?.reason?.ref ?: own`（放 `SeatWaitCopy.kt` 旁），三处点击都用它决定打开哪个对话。

- [ ] **Step 1:** 在 `SeatWaitCopyTest` 加 `jumpTargetPrefersRef`（Held 带 ref → ref；Running → own）。
- [ ] **Step 2:** 跑测试，预期失败。
- [ ] **Step 3:** 实现 `jumpTarget` 并接到三处点击；聊天页那一行在 `ref != null` 时可点，点了 `switchTo(ref)`。
- [ ] **Step 4:** 测试 PASS，`./gradlew :app:compileDebugKotlin` 通过。
- [ ] **Step 5:** 提交：`feat(task): 点「等「对话」用完手机」跳到占着手机的那个对话 (#839)`

### Task 5: 聊天页只留并行路径

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/chat/ui/ChatSession.kt:112-127`（删 `parallelOn`、`parallel()`、`refreshParallel()`、`parallelFlag`；`ownTask()` 改为 `shownTask.value?.first == _ui.value.conversationId`）
- Modify: `ChatViewModel.kt:102-103`（删 `parallelFlag` 参数）、`:355`（删不分对话套状态的 collect）、`:356`（去掉 `session.parallel()` 条件）、`:427`（删 `canSwitch()` 及调用方的灰掉逻辑，调用方按「恒可切」简化）
- Modify: `ChatConversationOps.kt`（`switchTo` 删串行回退；`openConversation` 直接 `return switchTo(id)` 之后的串行代码删掉，保留开头 `deskParent` 与 `clearUnnumberedMainChoice`；`newConversation` 只留并行分支；`:323` 恒 `store.newConversation()`）
- Modify: `ChatSendOps.kt:307-…`（删「等前一个任务做完」整段及 `pendingStart` 若因此无用）、`:428`（`act` 只留 scoped 路径，删 `serial` 参数并改所有调用点）
- Modify: `ChatLiveFollow.kt:240`（恒 `unpark(id)`）
- Modify: `MikaApp.kt:581`（删 `parallelFlag = …`）
- Modify: `res/values*/` 删 `chat_wait_previous_task`（若无其他引用）
- Modify: PR #928 加的 `DrawerBusyHint.kt` 不在 main 上，不涉及；侧栏/顶栏里依 `canSwitch()` 的 `enabled` 改为恒 true 或删参数（`grep -rn "canSwitch" app/src/main`）
- Test: `ChatViewModelTest.kt`、`DeskChatOpsTest.kt`

**Interfaces:**
- Consumes: 无（纯界面）
- Produces: `ChatViewModel` 构造少了 `parallelFlag`；`act(scoped: (String) -> Unit)`。

- [ ] **Step 1:** 改测试：删只测串行行为的用例（构造里 `parallelFlag = { false }` 的、断言「忙时不能切换/新建」的）；原 `parallelFlag = { true }` 的用例去掉该参数后保留。新增 `switchingWhileAnotherConversationRunsKeepsItsTask`（若已有等价的并行用例则不新增）。
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*ChatViewModelTest' --tests '*DeskChatOpsTest'`，预期编译失败。
- [ ] **Step 3:** 按 Files 删串行分支。
- [ ] **Step 4:** 同一命令 PASS；`./gradlew :app:testDebugUnitTest` 全量 PASS。
- [ ] **Step 5:** 提交：`feat(chat): 任务进行中侧栏、新建按钮灰着点不动 → 聊天页只留按对话跟进任务的路径，切换、查看、新建随时可用 (#839)`

### Task 6: 设置与开关

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/SpeedFlags.kt:10,19,51`（删 `parallelTasks`；解析时旧存储里的键被忽略）
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/DeveloperOptionsScreen.kt:38`（删开关行）；删字符串 `dev_parallel`、`dev_parallel_sub`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt:193,257-262`（`parallel` 恒为 `ParallelSetting(parallelMax, detect(sdk, lowRam, parallelMax))`，删 `runtimeLimit` 相关第二块屏判断）
- Modify: `DuringWorkScreen.kt` 的 `parallel: ParallelSetting?` 改为非空（若只有这一个调用方）
- Modify: `res/values*/strings_settings.xml` 的 `settings_parallel`、`settings_parallel_sub` 改为 Global Constraints 里的文案；`strings_task.xml` 的 `parallel_needs_14`、`parallel_low_ram` 改成说「同时操作手机」（中文：「同时操作手机需要安卓 14 或更新的系统」「这台手机内存较小，一次只操作一个 App」）
- Modify: `FeatureFlags.kt:60` KDoc 改为座位含义
- Test: `DeveloperSwitchesTest.kt`、core 里 `SpeedFlags` 的解析测试（若有）

- [ ] **Step 1:** 改测试：`DeveloperSwitchesTest` 去掉 `parallel-tasks`；core 加 `legacyParallelTasksKeyIsIgnored`（含 `"parallelTasks":true` 的旧 JSON 能解析，其余字段照旧）。
- [ ] **Step 2:** `./gradlew :core:test :app:testDebugUnitTest --tests '*DeveloperSwitchesTest'`，预期失败。
- [ ] **Step 3:** 按 Files 改。`grep -rn "parallelTasks" app core` 应无结果。
- [ ] **Step 4:** `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug` 通过。
- [ ] **Step 5:** 提交：`feat(settings): 「同时进行的任务」只在开发者开关里 → 改为所有版本都有的「同时操作手机的任务」，删除 parallelTasks 开关 (#839)`

### Task 7: 真机回归与 PR

测试机：安卓 14（`svc power stayon true` 保持，不锁屏、不输入 PIN）。装 debug 包后按下表逐项走，结果写进 PR 描述。

- [ ] 单任务完整流程：发消息、模型提问并回答、确认、接管、取消、结果通知、画中画——和改动前一致。
- [ ] 对话 A 在操作 App，切到对话 B 纯聊天发消息 → 两边同时进行；切回 A 能看到它的进度。
- [ ] 任务进行中点侧栏别的对话、新建对话、新建项目、顶栏新建 → 都能进。
- [ ] 设置里座位调成 1：A 操作 App 时让 B 也打开 App → B 显示「等「A 的标题」用完手机」；点这一行跳到 A；A 收尾后 B 自动接上。
- [ ] 等座位时停止 B → B 立即停下，A 不受影响。
- [ ] 锁屏回锁（需用户在场配合解锁授权，不由我们输入 PIN）：若无法安排就在 PR 里写明未验证，靠 `phoneUsersIgnoreChatOnlyRuns` 单测覆盖。
- [ ] `./gradlew :app:testDebugUnitTest :core:test :app:lintDebug` 全绿；推送 `feat/phone-seats`，开 PR，正文写明：关闭 #928 不合并、发布版用户行为变化、未在安卓 14 以下真机验证。
