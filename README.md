# onyx-peer-http-query

Onyx Peer HTTP Query provides an inbuilt HTTP server to service replica and
cluster queries that can be directed at Onyx nodes. One use case is to provide
a health check for your Onyx nodes, as it becomes easy to determine what a
node's view of the cluster is.

### HTTP Server

This library exposes an HTTP server to service replica and cluster queries across languages.

To use it, add onyx-peer-http-query to your dependencies:
```
[org.onyxplatform/onyx-peer-http-query "0.10.0.1-SNAPSHOT"]
```

Require onyx.http-query in your peer bootup namespace:

```clojure
(:require [onyx.http-query])
```


And add the following lines to Onyx's peer-config
```
 :onyx.query/server? true
 :onyx.query.server/port 8080
```

In addition, you can optionally add the IP to listen on with
```
 :onyx.query.server/ip "127.0.0.1"
```

JMX selectors can be whitelisted/queried via the peer-config:
e.g.
```
 :onyx.query.server/metrics-selectors ["org.onyxplatform:*" "com.amazonaws.management:*"]
```

The default behaviour is
```
 :onyx.query.server/metrics-selectors ["*:*"]
```

Individual metrics tags can be blacklisted via the peer-config:
```
 :onyx.query.server/metrics-blacklist [#"blacklisted_tag1" #"blacklistregex.*"]
```

### Accessing the HTTP server

Then query it to get a view of that nodes understanding of the cluster:

```
$ http --json http://localhost:8080/replica/peers
```

```json
HTTP/1.1 200 OK
Content-Length: 197
Content-Type: application/json
Date: Tue, 23 Feb 2016 03:35:08 GMT
Server: Jetty(9.2.10.v20150310)

{
    "as-of-entry": 12,
    "as-of-timestamp": 1456108757818,
    "result": [
        "e52df81d-38c9-44e6-9e3d-177d3e83292b",
        "fd4725f9-3429-49eb-840d-6c3e29cecc41",
        "fc933dda-7260-4547-93fc-241a02ca599a"
    ],
    "status": "success"
}
```

Note `as-of-entry` and `as-of-timestamp`. By comparing `as-of-entry` between
nodes, you can discover whether a node is lagging behind the cluster.

Further API endpoints are [described here](doc/server-api.md).

### Endpoints 

The Replica Query Server has a number of endpoints for accessing the
information about a running Onyx cluster. Below we display the HTTP method, the
URI, the docstring for the route, and any associated parameters that it takes
in its query string.

#### Summary

- `/health`
- `/peergroup/heartbeat`
- `/peergroup/health`
- `/network/media-driver/active`
- `/metrics`
- `/state`
- `/job/catalog`
- `/job/flow-conditions`
- `/job/lifecycles`
- `/job/task`
- `/job/triggers`
- `/job/windows`
- `/job/workflow`
- `/job/exception`
- `/replica`
- `/replica/completed-jobs`
- `/replica/job-allocations`
- `/replica/job-scheduler`
- `/replica/jobs`
- `/replica/killed-jobs`
- `/replica/peer-site`
- `/replica/peer-state`
- `/replica/peers`
- `/replica/task-allocations`
- `/replica/allocation-version`
- `/replica/task-scheduler`
- `/replica/tasks`

---

---

##### Route

`[:get]` `/health`

##### Query Params Schema

`{"threshold" java.lang.Long}`

##### Docstring

A single health check call to check whether the following statuses are healthy: `/network/media-driver/active`, `/peergroup/heartbeat`. Considers the peer group dead if timeout is greater than ?threshould=VALUE. Returns status 200 if healthy, 500 if unhealthy. Use this route for failure monitoring, automatic rebooting, etc.

---

##### Route

`[:get]` `/peergroup/heartbeat`


##### Query Params Schema

`{}`

##### Docstring

Returns the number of milliseconds since the peer group last heartbeated.

---

##### Route

`[:get]` `/peergroup/health`


##### Query Params Schema

`{}`

##### Docstring

A single health check call to check whether the peer group has heartbeated more recently than a threshold. Considers the peer group dead if timeout is greater than ?threshould=VALUE. Returns status 200 if healthy, 500 if unhealthy. Use this route for failure monitoring, automatic rebooting, etc.


##### Route

`[:get]` `/network/media-driver`


##### Query Params Schema

`{}`

##### Docstring

Returns a map describing the media driver status.
e.g.
```clojure
{:active true, 
 :driver-timeout-ms 10000, 
 :log "INFO: Aeron directory /var/folders/c5/2t4q99_53mz_c1h9hk12gn7h0000gn/T/aeron-lucas exists
       INFO: Aeron CnC file /var/folders/c5/2t4q99_53mz_c1h9hk12gn7h0000gn/T/aeron-lucas/cnc.dat exists
       INFO: Aeron toDriver consumer heartbeat is 687 ms old"}
```

---

##### Route

`[:get]` `/network/media-driver/active`


##### Query Params Schema

`{}`

##### Docstring

Returns a boolean for whether the media driver is active and has heartbeated within driver-timeout-ms milliseconds.

---

##### Route

`[:get]` `/metrics`


##### Query Params Schema

`{}`

##### Docstring

Returns any numeric JMX metrics contained in this VM, converted to prometheus tags.

---

##### Route

`[:get]` `/state`


##### Query Params Schema

`{"job-id" java.lang.String
  "task-id" java.lang.String
  "slot-id" java.lang.Long
  "window-id" java.lang.String
  "allocation-version" java.lang.Long}`

##### Docstring

Retrieve a task's window state for a particular job. Must supply the :allocation-version for the job. 
The allocation version can be looked up via the /replica/allocation-version, or by subscribing to the log and looking up the [:allocation-version job-id].

---


##### Route

`[:get]` `/job/catalog`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns catalog for this job.

---

##### Route

`[:get]` `/job/flow-conditions`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns flow conditions for this job.

---

##### Route

`[:get]` `/job/lifecycles`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns lifecycles for this job.

---

##### Route

`[:get]` `/job/task`


##### Query Params Schema

`{"job-id" java.lang.String, "task-id" java.lang.String}`

##### Docstring

Given a job id and task id, returns catalog entry for this task.

---

##### Route

`[:get]` `/job/triggers`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns triggers for this job.

---

##### Route

`[:get]` `/job/windows`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns windows for this job.

---

##### Route

`[:get]` `/job/workflow`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns workflow for this job.

---

##### Route

`[:get]` `/job/exception`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns the exception that killed this job, if one exists.

---

##### Route

`[:get]` `/replica`


##### Query Params Schema

``

##### Docstring

Derefences the replica as an immutable value.

---

##### Route

`[:get]` `/replica/completed-jobs`


##### Query Params Schema

``

##### Docstring

Lists all the job ids that have been completed.

---

##### Route

`[:get]` `/replica/job-allocations`


##### Query Params Schema

``

##### Docstring

Returns a map of job id -> task id -> peer ids, denoting which peers are assigned to which tasks.

---

##### Route

`[:get]` `/replica/job-scheduler`


##### Query Params Schema

##### Docstring

Returns the job scheduler for this tenancy of the cluster.

---

##### Route

`[:get]` `/replica/jobs`


##### Query Params Schema

``

##### Docstring

Lists all non-killed, non-completed job ids.

---

##### Route

`[:get]` `/replica/killed-jobs`


##### Query Params Schema

``

##### Docstring

Lists all the job ids that have been killed.

---

##### Route

`[:get]` `/replica/peer-site`


##### Query Params Schema

`{"peer-id" java.lang.String}`

##### Docstring

Given a peer id, returns the Aeron hostname and port that this peer advertises to the rest of the cluster.

---

##### Route

`[:get]` `/replica/peer-state`


##### Query Params Schema

`{"peer-id" java.lang.String}`

##### Docstring

Given a peer id, returns its current execution state (e.g. :idle, :active, etc).

---

##### Route

`[:get]` `/replica/peers`


##### Query Params Schema

``

##### Docstring

Lists all the peer ids.

---

##### Route

`[:get]` `/replica/task-allocations`


##### Query Params Schema

``

##### Docstring

Given a job id, returns a map of task id -> peer ids, 
  denoting which peers are assigned to which tasks for this job only.

---

##### Route

`[:get]` `/replica/allocation-version`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns the replica-version at which the job last rescheduled. This is important because the replica-version forms part of the vector clock that is used to determine ordering/validity of messages in the cluster, along with the barrier epoch. 


---

##### Route

`[:get]` `/replica/task-scheduler`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns the task scheduler for this job.

---

##### Route

`[:get]` `/replica/tasks`


##### Query Params Schema

`{"job-id" java.lang.String}`

##### Docstring

Given a job id, returns all the task ids for this job.


## License

Copyright © 2016 Distributed Masonry Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
