# onyx-peer-http-query

Onyx Peer HTTP Query provides an inbuilt HTTP server to service replica and
cluster queries that can be directed at Onyx nodes. One use case is to provide
a health check for your Onyx nodes, as it becomes easy to determine what a
node's view of the cluster is.

### HTTP Server

This library exposes an HTTP server to service replica and cluster queries across languages.

To use it, add onyx-peer-http-query to your dependencies:
```
[org.onyxplatform/onyx-peer-http-query "0.9.10.3-SNAPSHOT"]
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

- `/job/catalog`
- `/job/flow-conditions`
- `/job/lifecycles`
- `/job/task`
- `/job/triggers`
- `/job/windows`
- `/job/workflow`
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
- `/replica/task-scheduler`
- `/replica/tasks`

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

``

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
