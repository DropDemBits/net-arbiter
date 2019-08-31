% Net Arbiter Module
% Interface to the Java Net Arbiter
unit
module pervasive NetArbiter
    export 
        % Constants
        ~. ARB_ERROR_NO_ERROR,
        ~. ARB_ERROR_ALREADY_RUNNING,
        ~. ARB_ERROR_CONNECTION_REFUSED,
        ~. ARB_ERROR_INVALID_ARG,
        ~. ARB_ERROR_INVALID_RESPONSE,
        ~. ARB_ERROR_STARUP_FAILED,
        ~. STATUS_NEW,
        ~. STATUS_DISCONNECT,
        % Structures
        ~. var Arbiter, ~. Packet, ~. ConnectionStatus, errorToString
    
    %% Types %%%
    % Representation of a packet
    class pervasive Packet
        export
            % Exportable constants
            % size is modified by 'expand'
            var connID, var next, size,
            % Payload helpers
            getPayload, expand, cleanup
        
        % Packet data
        var connID : nat2 := 0
        var size : nat2 := 0
        var bytes : flexible array 1 .. 0 of nat1
        var next : ^Packet := nil
        
        /**
        * Gets the address of the payload data
        */
        fcn getPayload () : addressint
            result addr (bytes)
        end getPayload
        
        /**
        * Alters the size of the payload
        * Doesn't allow for the shrinkage of the payload size
        */
        proc expand (newSize : nat4)
            if newSize < size then
                return
            end if
        
            size := newSize
            new bytes, newSize
        end expand
        
        /**
        * Cleans up data related to the packet
        */
        proc cleanup ()
            free bytes
        end cleanup
    end Packet
    
    /**
    * Special packet that's used to indicate a connection change
    * Used for indication of new connections and disconnects
    */
    type pervasive ConnectionStatus :
        record    
            % Status indication of the packet
            statusType : int
            % Connection ID associated with the status update
            connID : int
            % Pointer to the next status update
            next : ^ConnectionStatus
        end record
    
    
    %% Error Codes %%
    const pervasive ARB_ERROR_NO_ERROR : int := 0
    % Current arbiter is already running
    const pervasive ARB_ERROR_ALREADY_RUNNING : int := -1
    % Connection to the arbiter was refused
    const pervasive ARB_ERROR_CONNECTION_REFUSED : int := -2
    
    % Invalid argument given
    const pervasive ARB_ERROR_INVALID_ARG : int := -3
    % Invalid response given
    const pervasive ARB_ERROR_INVALID_RESPONSE : int := -4
    
    % Unable to start arbiter process
    const pervasive ARB_ERROR_STARUP_FAILED : int := -5
    
    
    %% Constants %%
    const pervasive ITOHX : array 0 .. 15 of nat1 := init (
        ord ('0'), ord ('1'), ord ('2'), ord ('3'),
        ord ('4'), ord ('5'), ord ('6'), ord ('7'),
        ord ('8'), ord ('9'), ord ('A'), ord ('B'),
        ord ('C'), ord ('D'), ord ('E'), ord ('F')
    )
    
    % Status update for the ConnectionStatus structure
    const pervasive STATUS_NEW : int := 0
    const pervasive STATUS_DISCONNECT : int := 1
    
    /**
    * Converts an errno into a string
    */
    fcn pervasive errorToString (error : int) : string
        case error of
        label ARB_ERROR_NO_ERROR:               result "No Error"
        label ARB_ERROR_CONNECTION_REFUSED:     result "Connection Refused"
        label ARB_ERROR_ALREADY_RUNNING:        result "Already Running"
        label ARB_ERROR_INVALID_ARG:            result "Invalid Argument"
        label ARB_ERROR_INVALID_RESPONSE:       result "Invalid Arbiter Response"
        label ARB_ERROR_STARUP_FAILED:          result "Process Startup Failed"
        label :                                 result "Unknown error"
        end case
    end errorToString
    
    % Main net arbiter code
    class Arbiter
        import Sys
        export startup, shutdown, connectTo, disconnect, poll,
            getPacket, nextPacket, getStatus, nextStatus, writePacket, getError
            
        %% Normal constants %%
        const ARB_RESPONSE_NEW_CONNECTION : nat1    := ord ('N')
        const ARB_RESPONSE_CONNECTION_CLOSED : nat1 := ord ('R')
        const ARB_RESPONSE_ERROR : nat1             := ord ('W')
        const ARB_RESPONSE_COMMAND_SUCCESS : nat1   := ord ('S')
        
        
        %% Stateful variables %%
        % Current run state of the arbiter
        var isRunning : boolean := false
        
        % FilDes for the net socket
        var netFD : int := -1
        
        % Last error the arbiter encountered
        var errno : int := ARB_ERROR_NO_ERROR
        
        % List of pending packets
        var pendingPackets : ^Packet := nil
        var pendingPacketsTail : ^Packet := nil
        
        % List of pending connection status updates
        var pendingStatus : ^ConnectionStatus := nil
        var pendingStatusTail : ^ConnectionStatus := nil
        
        %% Asynchronous Dealsies %%
        % Is there a command being currently processed
        var isCommandInProgress : boolean := false
        % Response for the current command
        var responseParam : int4 := -1
        
        var lastBytes : int := 0
        
        
        %%% Private Functions %%%
        /**
        * Handles the response given by the net arbiter
        * 
        * Returns:
        * The response parameter, or -1 if an error occurred
        */
        fcn handleResponse (responseID : char) : int4
            % By this point, the command has been handled
            isCommandInProgress := false
        
            var param : int4 := -1
            var paramResponse : string
            
            % Handle the appropriate response
            case responseID of
            label 'S':
                % Command successful
                % Read in the return code
                read : netFD, paramResponse : 4
                
                if not strintok(paramResponse, 16) then
                    % Bad return code
                    errno := ARB_ERROR_INVALID_RESPONSE
                    result -1
                end if
                
                param := strint (paramResponse, 16)
            label 'W':
                % Error
                % Read in the error code
                read : netFD, paramResponse : 4
                
                if not strintok(paramResponse, 16) then
                    % Bad error code
                    errno := ARB_ERROR_INVALID_RESPONSE
                    result -1
                end if
                
                param := strint (paramResponse, 16)
                
                % Translate the error code
                case param of
                label 0:    param := ARB_ERROR_NO_ERROR
                label 1:    param := ARB_ERROR_CONNECTION_REFUSED
                label 2:    param := ARB_ERROR_INVALID_ARG
                label  :    param := ARB_ERROR_INVALID_RESPONSE
                end case
                errno := param
                
                if param not= ARB_ERROR_NO_ERROR then
                    param := -1
                end if
            % TODO: Handle remote connections
            label 'N', 'R':
                % New Connection, or Remote closed connection
                % Get the connection id
                read : netFD, paramResponse : 4
                
                if not strintok(paramResponse, 16) then
                    % Bad connection id
                    errno := ARB_ERROR_INVALID_RESPONSE
                    result -1
                end if
                
                var connID : nat2
                connID := strint (paramResponse, 16)
                
                % Build the connection status data
                var status : ^ConnectionStatus
                new status
                
                status -> connID := cheat (nat2, connID)
                status -> next := nil
                
                % Select the appropriate status
                if responseID = 'N' then
                    status -> statusType := STATUS_NEW
                else
                    status -> statusType := STATUS_DISCONNECT
                end if
                
                % Append to status update list
                if pendingStatus = nil then
                    % Empty queue
                    pendingStatus := status
                    pendingStatusTail := status
                else
                    % List with things in it
                    pendingStatusTail -> next := status
                    pendingStatusTail := status
                end if
            label :
                errno := ARB_ERROR_INVALID_RESPONSE
                result -1
            end case

            result param
        end handleResponse
        
        /**
        * Handles the current incoming packet
        */
        proc handlePacket ()
            % Packet Format: [connID][size][payload]
            var connID, payloadSize : nat4
            var numStr : string := ""
            
            % Get connID
            read : netFD, numStr : 4
            connID := strint (numStr, 16)
            
            % Get payload size
            read : netFD, numStr : 4
            payloadSize := strint (numStr, 16)
            
            if payloadSize = 0 then
                % Ignore 0-length packets (used for special data)
                return
            end if
            
            % Build the packet data
            var packet : ^Packet
            new packet
            
            packet -> connID := cheat (nat2, connID)
            packet -> next := nil
            
            % Copy the payload data
            packet -> expand (cheat (nat2, payloadSize))
            
            % Read in the payload
            var payload : array 1 .. payloadSize of nat1
            read : netFD, payload : payloadSize
            
            for i : 0 .. payloadSize - 1
                nat1 @ (packet -> getPayload () + i) := payload (i + 1)
            end for
            
            
            % Append to packet list
            if pendingPackets = nil then
                % Empty queue
                pendingPackets := packet
                pendingPacketsTail := packet
            else
                % List with things in it
                pendingPacketsTail -> next := packet
                pendingPacketsTail := packet
            end if
        end handlePacket
        
        %%% Exported Functions %%%
        /**
        * Gets the most recent error encountered by the arbiter
        * 
        * Valid return values:
        * ARB_ERROR_NO_ERROR:
        *   No error has occured
        * ARB_ERROR_ALREADY_RUNNING:
        *   The arbiter is already running
        * ARB_ERROR_CONNECTION_REFUSED:
        *   A connection to the arbiter or remote arbiter was refused
        * ARB_ERROR_INVALID_ARG:
        *   An invalid argument was given
        * ARB_ERROR_STARUP_FAILED:
        *   An error was encountered while starting up the arbiter process
        */
        fcn getError () : int
            result errno
        end getError
        
        /**
        * Polls the arbiter to see if there is any incoming data
        * Must be called before a getPacket ()
        *
        * Returns:
        * true if there is any pending data, false otherwise
        */
        fcn poll () : boolean
            if Net.BytesAvailable (netFD) = 0 then
                % Nothing in the buffer
                result false
            end if
        
            % There is some pending data available
            % It is either a response, or a packet
            loop
                exit when Net.BytesAvailable (netFD) = 0
                
                % Get the response id
                var responseID : char
                read : netFD, responseID : 1
                
                case responseID of
                label 'G':
                    responseParam := -1
                    handlePacket()
                label :
                    responseParam := handleResponse(responseID)
                end case
            end loop
            
            result true
        end poll
        
        /**
        * Gets the most recent packet in the queue
        */
        fcn getPacket () : ^Packet
            result pendingPackets
        end getPacket
        
        /**
        * Pops the given packet from the current queue and moves on to the next
        * one
        *
        * Returns:
        * True if there is more packets to process, false otherwise
        */
        fcn nextPacket () : boolean
            if pendingPackets = nil then
                % Nothing inside of the queue
                result false
            end if
        
            var packet : ^Packet := pendingPackets
            
            % Advance the queue
            pendingPackets := pendingPackets -> next
            
            % Done with the packet
            packet -> cleanup ()
            free packet
            
            if pendingPackets = nil then
                % Queue is empty
                pendingPacketsTail := nil
                result false
            end if
            
            % More packets to process
            result true
        end nextPacket
        
        /**
        * Gets the most recent connection status update in the queue
        */
        fcn getStatus () : ^ConnectionStatus
            result pendingStatus
        end getStatus
        
        /**
        * Pops the current update from the current queue and moves on to the
        * next one
        *
        * Returns:
        * True if there is more updates to process, false otherwise
        */
        fcn nextStatus () : boolean
            if pendingStatus = nil then
                % Nothing inside of the queue
                result false
            end if
        
            var status : ^ConnectionStatus := pendingStatus
            
            % Advance the queue
            pendingStatus := pendingStatus -> next
            
            % Done with the status update
            free status
            
            if pendingStatus = nil then
                % Queue is empty
                pendingStatusTail := nil
                result false
            end if
            
            % More status updates to process
            result true
        end nextStatus
        
        /**
        * Sends data to a remote connection
        */
        fcn writePacket (connID : int4, byteData : array 1 .. * of nat1) : int
            % Command format: P[connID][size][payload ...]
            % Packet length: CmdID (1) + ConnID (4) + Size (4) + Payload (???)
            var packetLength : nat4 := 1 + 4 + 4 + upper (byteData)
            var arbData : array 1 .. packetLength of nat1
            
            % Command ID
            arbData (1) := ord ('P')
            
            % Connection ID
            arbData (2) := ITOHX ((connID shr 12) & 16#0F)
            arbData (3) := ITOHX ((connID shr  8) & 16#0F)
            arbData (4) := ITOHX ((connID shr  4) & 16#0F)
            arbData (5) := ITOHX ((connID shr  0) & 16#0F)
            
            % Payload size
            arbData (6) := ITOHX ((upper (byteData) shr 12) & 16#0F)
            arbData (7) := ITOHX ((upper (byteData) shr  8) & 16#0F)
            arbData (8) := ITOHX ((upper (byteData) shr  4) & 16#0F)
            arbData (9) := ITOHX ((upper (byteData) shr  0) & 16#0F)
            
            % Payload
            for i : 1 .. upper (byteData)
                arbData (9 + i) := byteData (i)
            end for
            
            /*for i : 1 .. packetLength
                put chr(arbData (i)) ..
            end for
            put ""*/
            
            % Send the packet
            write : netFD, arbData : packetLength
            
            % Wait for the response
            errno := ARB_ERROR_NO_ERROR
            loop
                exit when not isCommandInProgress
                var dummy := poll ()
            end loop
            
            result 0
        end writePacket
        
        /**
        * Connects to a remote arbiter
        * 
        * Parameters:
        * host:     The address of the host to connect to (can be a domain name or)
        *           a valid ip address
        * port:     The specific port of the host to connect to
        *
        * Returns:
        * The connection ID to the remote arbiter. If it is negative, then an error
        * has occurred.
        * 
        * Errors:
        * ARB_ERROR_INVALID_ARG:
        *   If the host name given was invalid
        * ARB_ERROR_CONNECTION_REFUSED:
        *   If connection to the remote arbiter was refused
        */
        fcn connectTo (host : string, port : nat2) : int4
            % Command format: C[port][hostname]
            % Packet length: CmdId (1) + Port (4) + StrLen (2) + String (0 .. 255)
            const packetLength : int := 1 + 4 + (2 + length (host))
            var arbConnect : array 1 .. packetLength of nat1
            
            % Deal with command state
            if isCommandInProgress then
                % Command is in progress
                result -1
            end if
            isCommandInProgress := true
            
            arbConnect (1) := ord ('C')
            
            % Port
            arbConnect (2) := ITOHX ((port shr 12) & 16#0F)
            arbConnect (3) := ITOHX ((port shr  8) & 16#0F)
            arbConnect (4) := ITOHX ((port shr  4) & 16#0F)
            arbConnect (5) := ITOHX ((port shr  0) & 16#0F)
            
            % String length
            arbConnect (6) := ITOHX ((length (host) shr  4) & 16#0F)
            arbConnect (7) := ITOHX ((length (host) shr  0) & 16#0F)
            
            % String
            for i : 1 .. length (host)
                arbConnect (7 + i) := ord (host (i))
            end for
            
            % Send the command
            write : netFD, arbConnect : upper (arbConnect)
            
            % Wait for the response
            errno := ARB_ERROR_NO_ERROR
            loop
                exit when not isCommandInProgress
                var dummy := poll ()
            end loop
            
            % responseParam has the connection id
            result responseParam
        end connectTo
        
        /**
        * Disconnects from a remote arbiter
        * 
        * Parameters:
        * connID:   The destination location of the connection ID
        * 
        * Errors:
        * ARB_ERROR_INVALID_ARG:
        *   If the connection id given was invalid
        */
        proc disconnect (connID : int4)
            % Command format: D[connID]
            % Packet length: CmdId (1) + Connection ID (4)
            const packetLength : int := 1 + 4
            var arbDisconnect : array 1 .. packetLength of nat1
            
            % Deal with command state
            if isCommandInProgress then
                return
            end if
            isCommandInProgress := true
            
            % Header
            arbDisconnect (1) := ord ('D')
            
            % Connection ID
            arbDisconnect (2) := ITOHX ((connID shr 12) & 16#0F)
            arbDisconnect (3) := ITOHX ((connID shr  8) & 16#0F)
            arbDisconnect (4) := ITOHX ((connID shr  4) & 16#0F)
            arbDisconnect (5) := ITOHX ((connID shr  0) & 16#0F)
            
            % Send the command
            write : netFD, arbDisconnect : upper (arbDisconnect)
            
            % Wait for the response
            errno := ARB_ERROR_NO_ERROR
            loop
                exit when not isCommandInProgress
                var dummy := poll ()
            end loop
        end disconnect
        
        /**
        * Launches the net arbiter
        *
        * As communication with the net arbiter is done over a network socket, the
        * local port of the arbiter needs to be specified
        *
        * The listening port is not required for the operation of the arbiter, but
        * it is used to allow the arbiter to accept connections from other arbiters
        * If this behaviour is not desired, then the listenPort parameter can be
        * set to 0
        *
        * Parameters:
        * arbiterPort:  The port that the arbiter interface will be at
        * listenPort:   The port that the arbiter will accept connections from
        *
        * Errors:
        * ARB_ERROR_CONNECTION_REFUSED:
        *   If a connection attempt was refused
        * ARB_ERROR_INVALID_ARG:
        *   If the arbiterPort is equal to listenPort
        *   If the arbiterPort is 0
        */
        proc startup (arbiterPort : nat2, listenPort : nat2)
            if isRunning then
                % The net arbiter is already running
                errno := ARB_ERROR_ALREADY_RUNNING
                return
            end if
            
            if arbiterPort = listenPort or arbiterPort = 0 then
                % - Endpoint port can't be the same as the listening port
                % - Arbiter port can't be 0
                errno := ARB_ERROR_INVALID_ARG
                return
            end if
        
            % TODO: Start the net arbiter process
            const CMD_STRING : string := "java -cp ../out/production/turing-net-arbiter ddb.io.netarbiter.NetArbiter"
            
            % Build the command string
            var realCommand : string := ""
            realCommand += CMD_STRING
            realCommand += " --endpointPort=" + natstr(arbiterPort)
            
            if listenPort not= 0 then
                % Append optional listening port
                realCommand += " --listenPort=" + natstr(listenPort)
            end if
            
            % Launch the arbiter process
            put "Starting arbiter with command: \"", realCommand, '"'
            
            /*if not Sys.Exec (realCommand) then
                % Error in starting the net arbiter process
                % Note: "Error.Last" contains more information about the
                % specific error
                errno := ARB_ERROR_STARUP_FAILED
                return
            end if*/
            
            % Wait for a bit to allow the arbiter to initialize
            delay (100)
            
            % Connect to the net arbiter
            netFD := Net.OpenConnectionBinary ("localhost", arbiterPort)
            
            if netFD < 0 then
                % Net socket connection error (connection refused)
                errno := ARB_ERROR_CONNECTION_REFUSED
                return
            end if
            
            % Connection to arbiter successful
            isRunning := true
            errno := ARB_ERROR_NO_ERROR
        end startup
        
        /**
        * Stops the current arbiter
        */
        proc shutdown ()
            if not isRunning then
                % Arbiter has been stopped, don't need to do anything
                return
            end if
            
            % Cleanup any remaining packets
            var nextPacket : ^Packet := pendingPackets
            
            loop
                exit when nextPacket = nil
                
                % Advance to the next packet & free the current one
                var packet : ^Packet := nextPacket
                nextPacket := nextPacket -> next
                
                packet -> cleanup ()
                free packet
            end loop
            
            % Empty the list
            pendingPackets := nil
            pendingPacketsTail := nil

            % Command format: X
            % Send the stop command
            const arbStop : nat1 := ord ('X')
            write : netFD, arbStop : 1
            Net.CloseConnection (netFD)
            
            isRunning := false
            errno := ARB_ERROR_NO_ERROR
        end shutdown
    end Arbiter
end NetArbiter