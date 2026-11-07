# TASK-924 todo (poteto Feature playbook, verbatim steps first)

1. `how` over the affected subsystem.
2. `architect` for parallel design exploration.
3. Write the throughput checkpoint as four todo items.
   - **Blocking first steps.** Gates run before fan-out: the subsystem map (running) and the design forks settle before any code.
   - **Independent workstreams.** codec/manifest + export gate vs import routing vs UI strings are largely disjoint files, but the shared manifest vocabulary argues one owner (see below).
   - **Shared mutable state.** the bundle manifest vocabulary (ModelBundleCodec owns it) and the presence-restore semantics; serialize the design of both in this chat, delegate implementation against the frozen shape.
   - **Smallest safe decomposition.** one code delegate in a worktree: the feature is code-coupled (one migration of the transfer pipeline), single owner with the checkpoint inline per the playbook's last paragraph.
4. Delegate code-writing to a subagent (feature model, own worktree, named data shape + success criteria).
5. Verify on the matching surface. (JVM unit suite here; DEVICE TRIAL BLOCKED: phone off adb, maintainer asleep; the device pass stays an explicit open gate before any push.)
6. Rebase into small, ordered commits. (local only; no push without maintainer approval)
7. If the design is contested, `interrogate` before shipping. (forks recorded in the trail; panel only if a fork stays contested after evidence)
8. Run **Opening a PR**. skip: this repo pushes directly (feedback_git_workflow) and nothing gets pushed without maintainer approval (feedback_no_auto_pr_or_comments); local ordered commits held for morning review instead.

## Task-specific items
- [x] Write .audit trail start row
- [x] Subsystem map from explorer
- [x] Design: manifest origin identity + import routing + presence restore; forks recorded (trail rows 2-5)
- [x] Delegate implementation (worktree; 4 commits cherry-picked to main)
- [x] Review diff myself (read in full; trail row 9)
- [x] Gates: /simplify (4 angles, row 12) + /code-review high (6 findings fixed, row 15) + full unit suite (2420/0/0/7 --rerun-tasks, evidence in .audit/task-924-verification.md)
- [x] Ordered local commits (six: d695ac4e f652feaf 927267c5 9a4d3086 d2a039c1 627d0cae; unpushed)
- [x] Morning report (this hand-back; #124 reply still held)
- [ ] DEVICE TRIAL (blocked overnight: phone off adb; checklist in TASK-924 incl. the overwrite case)
- [ ] MAINTAINER REVIEW + push approval
- [ ] TASK-925 (held to avoid same-file conflicts; resume after this lands)
