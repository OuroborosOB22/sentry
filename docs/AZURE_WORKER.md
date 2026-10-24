# Running jobs on an Azure virtual machine

Status: the hub side is written and tested against a fake `ssh` and `scp` with the real worker script. It has not been run against a real Azure machine. The test for that is the "Test connection" button plus one real rehearsal, listed under "First live check" below. Nothing here creates a cloud resource for you.

## How it works

The hub never sends a free-form shell command. It runs one program on the VM, `sentry-worker`, with a fixed list of commands: `capabilities`, `init`, `prepare`, `start`, `status`, `cancel`, `package`, `cleanup`. Every argument is checked against a strict pattern on both sides.

```
hub --scp source.zip--> VM:~/sentry-jobs/<job>/
hub --ssh sentry-worker start ...--> VM runs the engine in its own folder
hub <--scp out.zip-- VM
hub checks the schema, hashes and file types of everything that came back before it shows anything
```

Only files that `GET /api/projects/{id}/transfer-preview` lists are sent. Secrets, `.git`, build output and links are never packed.

## What you create by hand

1. A small Ubuntu VM (2 vCPU and 8 GB is enough for a demo). Allow inbound SSH only from your own address.
2. A user for the worker and an SSH key pair. Keep the private key outside every repository.
3. Run `worker/bootstrap-vm.sh` on the VM, then copy `engine/target/sentry-engine-0.1.0-cli.jar`, `mvnw` and `.mvn` into `~/sentry`, and `worker/sentry-worker` into a folder on the PATH.
4. Add the VM to the machine's `known_hosts` yourself. The hub refuses unknown hosts (`StrictHostKeyChecking=yes`).

## Hub settings

```
SENTRY_AZURE_ENABLED=true
SENTRY_AZURE_HOST=<vm address>
SENTRY_AZURE_USER=<worker user>
SENTRY_AZURE_IDENTITY_FILE=/path/to/private/key
SENTRY_AZURE_KNOWN_HOSTS_FILE=/path/to/known_hosts
```

## First live check

1. Settings page: choose AZURE_VM, press "Test connection". It should say connected and show the Java version.
2. Run one rehearsal on a small project. Compare the verdict with the same rehearsal on LOCAL.
3. Stop or delete the VM when done. A running VM costs money.

Azure's free account gives a limited credit for a limited time; check the current offer on Microsoft's page before relying on it.
