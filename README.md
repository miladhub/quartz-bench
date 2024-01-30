Quartz bench
===

Experimenting with concurrent [Quartz](https://github.com/quartz-scheduler/quartz)
executions using [quartz-mongodb](https://github.com/michaelklishin/quartz-mongodb).

# Build

```shell
$ mvn clean install
```

# Running against MongoDB

Clean the DB:
```shell
$ mongosh quartz
rs [direct: primary] quartz> db.dropDatabase();
{ ok: 1, dropped: 'quartz' }
rs [direct: primary] quartz>
```

On one tab, this will execute a one-shot after 30 seconds and connect to debug:

```shell
$ java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
  -cp "target/*" org.example.QuartzBench -props=mongo.properties -schedule=30
```

Right after this one, execute this other command on another tab, to create a
concurrent Quartz instance:

```shell
$ java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5006 \
  -cp "target/*" org.example.QuartzBench -props=mongo.properties
```

Add a debug breakpoint at `TriggerRunner:130` and connect to debug ports `5005`
and `5006`, creating two debugging sessions:

```
TriggerKey key = trigger.getKey();
if (lockManager.tryLock(key)) { // <-- HERE
    if (prepareForFire(noLaterThanDate, trigger)) {
        log.info("Acquired trigger: {}", trigger.getKey());
        triggers.put(trigger.getKey(), trigger);
    } else {
    	triggers.put(trigger.getKey(), trigger);
        lockManager.unlockAcquiredTrigger(trigger);
        triggers.remove(trigger.getKey());
    }
} else if (lockManager.relockExpired(key)) {
    log.info("Recovering trigger: {}", trigger.getKey());
    OperableTrigger recoveryTrigger = recoverer.doRecovery(trigger);
    lockManager.unlockAcquiredTrigger(trigger);
    if (recoveryTrigger != null && lockManager.tryLock(recoveryTrigger.getKey())) {
        log.info("Acquired trigger: {}", recoveryTrigger.getKey());
        triggers.put(recoveryTrigger.getKey(), recoveryTrigger);
    }
}
```

In the first session (`5005`), step into the `tryLock()` method and stop on the
`return true` statement:

```java
public boolean tryLock(TriggerKey key) {
    try {
        locksDao.lockTrigger(key);
        return true; // <- HERE
    } catch (MongoWriteException e) {
        log.info("Failed to lock trigger {}, reason: {}", key, e.getError());
    }
    return false;
}
```

This way, the first instance will have created the lock on DB - e.g.:

```shell
rs [direct: primary] quartz> db.quartz_locks.find();
[
  {
    _id: ObjectId("65b7bd4585a25c214ca9c7a3"),
    type: 't',
    keyGroup: 'DEFAULT',
    keyName: 'my-trigger',
    instanceId: 'AMAL14L6J4Y6W1706540307685',
    time: ISODate("2023-01-01T00:00:00.000Z")
  }
]
```

Now, change the `time` field of this document manually to a year in the past to
force an expiry - e.g.:

```shell
rs [direct: primary] quartz> db.quartz_locks.updateOne(
  {type: 't', keyGroup: 'DEFAULT', keyName: 'my-trigger'},
  {$set: {time: ISODate("2023-01-01T00:00:00.000Z")}}
)
{
  acknowledged: true,
  insertedId: null,
  matchedCount: 1,
  modifiedCount: 1,
  upsertedCount: 0
}
```

This way, the second instance should find the lock, detect it as expired, and
acquire its ownership. To allow this, unpause the second debug session.

The end result now should be that the lock has now a different `instanceId`:

```shell
rs [direct: primary] quartz> db.quartz_locks.find();
[
  {
    _id: ObjectId("65b7bd4585a25c214ca9c7a3"),
    type: 't',
    keyGroup: 'DEFAULT',
    keyName: 'my-trigger',
    instanceId: 'AMAL14L6J4Y6W1706540311474',
    time: ISODate("2024-01-29T15:00:16.082Z")
  }
]
```

However, this line has been printed on the first tab only, not the second:

```shell
Job job_42, fire: Mon Jan 29 14:43:07 CET 2024, previous: null, next: null
```

# Recovery

If recovery is enabled, when the second tab detects the lock expiry, it also
returns the current trigger among those eligible for execution, thus causing
the job execution on both tabs.

To replicate this, do the same as above, but add the `-recovery` flag on the
first tab:

```shell
$ java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
  -cp "target/*" org.example.QuartzBench -props=mongo.properties -schedule=30 \
  -recovery
```

At the end of the debug steps, both tabs should have printed the job execution
line.

# Running against Postgresql

To run against PostgreSQL,
* create the DB, user and tables using the two SQL scripts in this directory,
* change the properties file to `pg.properties` when invoking the program.

# References

* <https://github.com/michaelklishin/quartz-mongodb>
* <https://github.com/quartz-scheduler/quartz/blob/a5c4d27e963f51097f9b2777489d310a88897ca4/examples/src/main/java/org/quartz/examples/example13/ClusterExample.java#L35>
* <https://github.com/michaelklishin/quartz-mongodb/issues/208>
* <https://github.com/michaelklishin/quartz-mongodb/issues/226>
* <http://www.quartz-scheduler.org/documentation/2.4.0-SNAPSHOT/tutorials/tutorial-lesson-11.html>
