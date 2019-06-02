## Net Arbiter Specifications
-----

netFD := OpenConnectionBinary(address : string, port : int)
netFD := WaitForConnection(port : int, var address)

# Requirements
- Must be able to accept connections (regardless of "mode")
- Must be able to passthrough packets
- Must be able to send UDP packets at a reliable rate

# Milestones
- Simple Chat Client
  - Client commands
  - Accept new connections
  - Packet passthrough
  
# Notes:
 - Remote inbound and outbound connection ids are shared
 - Arbiter commands have asynchronous responses

# Protocols
## Arbiter  - Arbiter
Transparent to endpoints

Connection:
S -> D: Connection Establish 
D -> S: Ack (Establish)
D -> ED: New Connection
S -> ES: Connection establish

Disconnect:
S -> D: Disconnect Notify
(Close connection)

Transparent passthrough of data

Ack (Establish):
```arb1```
Disconnect Notify:
```arb2```
Data Receive:
```[size : 2][payload]```

## Endpoint - Arbiter
Exit:
```X```
It is the endpoint's responsibility for closing the connection.
All remote connections will be closed by the arbiter.

Connect To:
```C[port : 2][address : lstring]```

Disconnect:
```D[connID : 2]```

Send Data:
```P[connID : 2][size : 2][payload]```

Arbiter must ignore any invalid command id's and invalid commands

## Arbiter - Endpoint
New Connection (D -> ES)
```N[connID : 2]```

Connection Closed by Remote
```R[connID : 2]``` 

Error:
```W[errorCode : 2]```

Command Successful:
```S[param : 2]```

Data Receive:
```G[connID : 2][size : 2][payload]```

### Defined Commands:

| ID (char) | Name       | Payload                               | Description                                          |
|-----------|------------|---------------------------------------|------------------------------------------------------|
| 0x00('C') | CONNECT    | ```[port : 2][address : lstring]```   | Establishes a connection to a remote arbiter         |
| 0x00('D') | DISCONNECT | ```[connID : 2]```                    | Disconnects from the specified remote arbiter        |
| 0x00('X') | EXIT       | N/A                                   | Stops communication between the arbiter and endpoint |
| 0x00('P') | SEND_DATA  | ```[connID : 2][size : 2][payload]``` | Sends the given data to a remote arbiter             |
