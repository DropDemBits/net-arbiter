% Net Arbiter Module
% Interface to the Java Net Arbiter
unit
module pervasive NetArbiter
    export ~. var Arbiter, errorToString
    
    %% Error Codes %%
    const pervasive ARB_ERROR_NO_ERROR : int := 0
    % Current arbiter is already running
    const pervasive ARB_ERROR_ALREADY_RUNNING : int := -1
    % Connection to the arbiter was refused
    const pervasive ARB_ERROR_CONNECTION_REFUSED : int := -2
    
    % Invalid argumant given
    const pervasive ARB_ERROR_INVALID_ARG : int := -3
    
    
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
        label ARB_ERROR_INVALID_ARG:            result "Invalid argument"
        label :                                 result "Unknown error"
        end case
    end errorToString
    
    
    class Arbiter
        export startup, shutdown, connectTo, disconnect, getError
        %% Stateful variables %%
        % Only one instance of the net arbiter is allowed per-run
        var isRunning : boolean := false
        
        % FilDes for the net socket
        var netFD : int := -1
        
        % Last error the arbiter encountered
        var errno : int := ARB_ERROR_NO_ERROR
        
        var lastBytes : int := 0
        
        proc waitUntil ()
            if lastBytes >= Net.BytesAvailable (netFD) then
                return
            end if
            
            loop
                exit when Net.BytesAvailable (netFD) > lastBytes
            end loop
            
            lastBytes := Net.BytesAvailable (netFD)
        end waitUntil
        
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
        * Checks if the arbiter has incoming data
        */
        fcn hasPending (connID : nat2) : boolean
            result false
        end hasPending
        
        /*fcn readPacket (connID : nat2, var packet : Packet) : int
            result 0
        end readPacket*/
        
        /**
        * Sends data to a remote connection
        */
        fcn writePacket (connID : nat2, byteData : array 0 .. * of nat1) : int
            % TODO: Build packet
            
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
            waitUntil ()
            
            errno := ARB_ERROR_NO_ERROR
            result 0
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
            
            errno := ARB_ERROR_NO_ERROR
        end disconnect
        
        /**
        * Launches the net arbiter
        *
        * As communication with the net arbiter is done over a network socket, the
        * local port of the arbiter needs to be specified
        *
        * The listening port is not required for the operation of the arbiter, but
        * it is used to allow the arbiter to accept connections from other arbiters
        *
        * Parameters:
        * arbiterPort:  The port that the arbiter interface will be at
        * listenPort:   The port that the arbiter will accept connections from
        *
        * Errors:
        * ARB_ERROR_CONNECTION_REFUSED:
        *   If a connection attempt was refused
        */
        proc startup (arbiterPort : nat2, listenPort : nat2)
            if isRunning then
                % The net arbiter is already running
                errno := ARB_ERROR_ALREADY_RUNNING
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
            
            % Send the stop command
            const arbStop : array 1 .. 5 of nat1 := init (ord ('a'), ord ('r'), ord ('b'), ord (':'), ord ('X'))
            write : netFD, arbStop : 5
            Net.CloseConnection (netFD)
            
            isRunning := false
            errno := ARB_ERROR_NO_ERROR
        end shutdown
    end Arbiter
end NetArbiter