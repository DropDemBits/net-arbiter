% Net Arbiter Module
% Interface to the Java Net Arbiter
unit
module pervasive NetArbiter
    export ~. var Arbiter, ~. Packet, errorToString
    
    %% Types %%%
    type pervasive Packet :
    record
        connID : nat2
        size : nat2
        bytes : addressint
        next : ^Packet
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
    
    
    %% Constants %%
    const pervasive ITOHX : array 0 .. 15 of nat1 := init (
        ord ('0'), ord ('1'), ord ('2'), ord ('3'),
        ord ('4'), ord ('5'), ord ('6'), ord ('7'),
        ord ('8'), ord ('9'), ord ('A'), ord ('B'),
        ord ('C'), ord ('D'), ord ('E'), ord ('F')
    )
    
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
        label :                                 result "Unknown error"
        end case
    end errorToString
    
    % Main net arbiter code
    class Arbiter
        export startup, shutdown, connectTo, disconnect, poll, getPacket, nextPacket, writePacket, getError
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
        
        var lastBytes : int := 0
        
        
        %%% Private Functions %%%
        /**
        * Parses the response given by the net arbiter
        * 
        * Returns:
        * The response parameter, or -1 if an error occurred
        */
        fcn parseResponse () : int4
            var param : int4 := -1
            var responseHeader : array 1 .. 5 of nat1
            var paramResponse : string
            
            % Wait until a response from the arbiter is received
            if Net.BytesAvailable (netFD) =0 then
                loop
                    exit when Net.BytesAvailable (netFD) > 0
                end loop
            end if
            
            % Read in the response
            read : netFD, responseHeader : 5
            
            % Verify the response header
            const ARB_HEADER : string := "arb:"
            
            for i : 1 .. 4
                if chr (responseHeader (i)) not= ARB_HEADER (i) then
                    errno := ARB_ERROR_INVALID_RESPONSE
                    result -1
                end if
            end for
            
            % Handle the appropriate response
            var responseID : char := chr (responseHeader (5))
            case responseID of
            label 'E':
                % Connection Established
                % Read in the connection ID
                read : netFD, paramResponse : 4
                
                if not strintok(paramResponse, 16) then
                    % Bad connection ID
                    errno := ARB_ERROR_INVALID_RESPONSE
                    result -1
                end if
                
                param := strint (paramResponse, 16)
            label 'S':
                % Data sent successfully
                param := 0
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
            label :
                errno := ARB_ERROR_INVALID_RESPONSE
                result -1
            end case

            result param
        end parseResponse
        
        
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
        */
        fcn getError () : int
            result errno
        end getError
        
        /**
        * Polls the arbiter to see if there is incoming data
        * Must be called before a getPacket ()
        *
        * Returns:
        * true if there is any pending packets, false otherwise
        */
        fcn poll () : boolean
            if Net.BytesAvailable (netFD) = 0 then
                % Nothing in the buffer
                result false
            end if
        
            % There is some pending data available
            % Packet Format: [connID][size][payload]
            loop
                exit when Net.BytesAvailable (netFD) = 0
                
                var connID, payloadSize : nat4
                var numStr : string := ""
                
                % Get connID
                read : netFD, numStr : 4
                put numStr..
                connID := strint (numStr, 16)
                
                % Get payload size
                read : netFD, numStr : 4
                put numStr..
                payloadSize := strint (numStr, 16)
                
                % Read in the payload
                var payload : array 1 .. payloadSize of nat1
                read : netFD, payload : payloadSize
                
                % Build the packet data
                var packet : ^Packet
                new packet
                
                packet -> connID := cheat (nat2, connID)
                packet -> size := cheat (nat2, payloadSize)
                packet -> bytes := addr (payload)
                packet -> next := nil
                
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
                put ""
                
                /*put "RecvData: ", connID, " ", payloadSize, " " ..
                for i : 1 .. payloadSize
                    put chr (payload (i)) ..
                end for
                put ""*/
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
        * Pops the given packet from the current queue
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
        * Sends data to a remote connection
        */
        fcn writePacket (connID : int4, byteData : array 1 .. * of nat1) : int
            % Command format: [connID][size][payload ...]
            % Packet length: Header (5) + ConnID (4) + Size (4) + Payload (???)
            var packetLength : nat4 := 4 + 4 + upper (byteData)
            var arbData : array 1 .. packetLength of nat1
            
            % Connection ID
            arbData (1) := ITOHX ((connID shr 12) & 16#0F)
            arbData (2) := ITOHX ((connID shr  8) & 16#0F)
            arbData (3) := ITOHX ((connID shr  4) & 16#0F)
            arbData (4) := ITOHX ((connID shr  0) & 16#0F)
            
            % Payload size
            arbData (5) := ITOHX ((upper (byteData) shr 12) & 16#0F)
            arbData (6) := ITOHX ((upper (byteData) shr  8) & 16#0F)
            arbData (7) := ITOHX ((upper (byteData) shr  4) & 16#0F)
            arbData (8) := ITOHX ((upper (byteData) shr  0) & 16#0F)
            
            % Payload
            for i : 1 .. upper (byteData)
                arbData (8 + i) := byteData (i)
            end for
            
            % Send the packet
            write : netFD, arbData : packetLength
            
            errno := ARB_ERROR_NO_ERROR
            result parseResponse ()
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
            % Command format: arb:C[port][hostname]
            % Packet length: Header (5) + Port (4) + StrLen (2) + String (0 .. 255)
            const packetLength : int := 5 + 4 + (2 + length (host))
            var arbConnect : array 1 .. packetLength of nat1
            
            arbConnect (1) := ord ('a')
            arbConnect (2) := ord ('r')
            arbConnect (3) := ord ('b')
            arbConnect (4) := ord (':')
            arbConnect (5) := ord ('C')
            
            % Port
            arbConnect (6) := ITOHX ((port shr 12) & 16#0F)
            arbConnect (7) := ITOHX ((port shr  8) & 16#0F)
            arbConnect (8) := ITOHX ((port shr  4) & 16#0F)
            arbConnect (9) := ITOHX ((port shr  0) & 16#0F)
            
            % String length
            arbConnect (10) := ITOHX ((length (host) shr  4) & 16#0F)
            arbConnect (11) := ITOHX ((length (host) shr  0) & 16#0F)
            
            % String
            for i : 1 .. length (host)
                arbConnect (11 + i) := ord (host (i))
            end for
            
            % Send the command
            write : netFD, arbConnect : upper (arbConnect)
            
            % Check for an error
            errno := ARB_ERROR_NO_ERROR
            var connID := parseResponse ()
            result connID
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
            % Command format: arb:D[connID]
            % Packet length: Header (5) + Connection ID (4)
            const packetLength : int := 5 + 4
            var arbDisconnect : array 1 .. packetLength of nat1
            
            % Header
            arbDisconnect (1) := ord ('a')
            arbDisconnect (2) := ord ('r')
            arbDisconnect (3) := ord ('b')
            arbDisconnect (4) := ord (':')
            arbDisconnect (5) := ord ('D')
            
            % Connection ID
            arbDisconnect (6) := ITOHX ((connID shr 12) & 16#0F)
            arbDisconnect (7) := ITOHX ((connID shr  8) & 16#0F)
            arbDisconnect (8) := ITOHX ((connID shr  4) & 16#0F)
            arbDisconnect (9) := ITOHX ((connID shr  0) & 16#0F)
            
            % Send the command
            write : netFD, arbDisconnect : upper (arbDisconnect)
            
            % Check to see if an error occurred
            errno := ARB_ERROR_NO_ERROR
            
            if Net.BytesAvailable (netFD) > 0 then
                var dummy := parseResponse ()
                return
            end if
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
            
            % Command format: arb:X
            % Send the stop command
            const arbStop : array 1 .. 5 of nat1 := init (ord ('a'), ord ('r'), ord ('b'), ord (':'), ord ('X'))
            write : netFD, arbStop : 5
            Net.CloseConnection (netFD)
            
            isRunning := false
            errno := ARB_ERROR_NO_ERROR
        end shutdown
    end Arbiter
end NetArbiter