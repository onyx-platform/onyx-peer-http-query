# onyx-peer-http-query

Onyx Peer HTTP Query provides an inbuilt HTTP server to service replica and
cluster queries that can be directed at Onyx nodes. One use case is to provide
a health check for your Onyx nodes, as it becomes easy to determine what a
node's view of the cluster is.

### HTTP Server

This library exposes an HTTP server to service replica and cluster queries across languages.

To use it, add onyx-peer-http-query to your dependencies:
```
[org.onyxplatform/onyx-peer-http-query "0.9.10.0-SNAPSHOT"]
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

## License

Copyright © 2016 Distributed Masonry Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
