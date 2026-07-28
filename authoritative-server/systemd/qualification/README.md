# Live Linux worker qualification

Run `run-linux-worker-qualification.ps1` from a Windows host with Docker
Desktop's Linux engine. The runner builds the real packaged Kotlin worker,
stages only the required immutable assets in a temporary Docker context, and
boots pinned Ubuntu 24.04 with systemd as PID 1.

The privileged container is a disposable qualification environment, not a
production deployment image. Privilege is required so nested systemd can apply
and expose cgroup v2 controls. The test verifies the checked-in production unit
through an authenticated worker handshake and controlled SIGKILL, scheduled
recycle, watchdog, and JVM-OOM failures. It also inspects the kernel-applied
CPU, memory, no-swap, task, and file-descriptor ceilings and proves ruleset and
secret-file permissions with the real service identity.

The script always removes its container and temporary build context. The local
qualification image contains only public game assets and build outputs and may
be removed independently after the run.
