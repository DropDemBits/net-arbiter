% Arbiter Test
% Tests the functions of the arbiter
import NetArbiter in "net_arbiter.t"


%%% Types & Classes %%%
monitor class mutex
    export acquire, canAcquire, release
    
    var canLock : boolean := true
    var waitingQueue : condition
    
    proc acquire ()
        if not canLock then
            wait waitingQueue
        end if
        
        % Only 1 process can acquire the lock at a time
        canLock := false
    end acquire
    
    fcn canAcquire () : boolean
        result canLock
    end canAcquire
    
    proc release ()
        assert not canLock
        
        % Release the lock
        canLock := true
        
        % Wake whoever is in the waiting queue
        signal waitingQueue
    end release
end mutex


%%% Utilities %%%
proc cleanUp (var arbiter : ^ Arbiter)
    put "Stopping arbiter"
    arbiter -> shutdown ()
    free arbiter
end cleanUp


%%% Main Code %%%
var shouldRun : boolean := true

var basePort : int := 7007
var portLock, screenLock : ^mutex

process EndpointTest ()
    var ourPort : int
    var connID : int4 := 0
    var arb : ^ Arbiter
    var winID : int
    
    % Statistics
    var sendTime, sendCycles : nat4 := 0
    var recvTime, recvCycles : nat4 := 0
    var recvPeriod : nat4 := 0
    
    var recvTimer : nat4 := Time.Elapsed
    var recvCounter : nat4 := 0
    
    new Arbiter, arb
    
    % Get usage port
    portLock -> acquire ()
    ourPort := basePort
    basePort += 1
    portLock -> release ()
    
    var endpointInstance : int := ourPort - 7007
    if endpointInstance = 0 then
        winID := Window.Open ("graphics;title:Arbiter " + intstr(endpointInstance) + ";position:left;top")
    else
        winID := Window.Open ("graphics;title:Arbiter " + intstr(endpointInstance) + ";position:right;top")
    end if
    
    Window.Select (winID)
    
    put "Starting arbiter"
    arb -> startup (ourPort, 0)
    if arb -> getError () not= 0 then
        put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
        cleanUp (arb)
        
        delay (1000)
        Window.Close (winID)
        shouldRun := false
        return
    end if
    
    %% Connect to the remote arbiter %%
    put "Connecting to a remote arbiter"
    connID := arb -> connectTo ("localhost", 8087)
    
    if connID >= 0 then
        put "Connected to remote arbiter (CID: ", connID, ")"
    else
        put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
        arb -> shutdown ()
        return
    end if
    
    %% Send the test packet data %%
    const TEST_STRING : string := "hello, networking world!"
    var len : int := 0
    
    put "Sending test data: ", TEST_STRING
    var data : array 1 .. length (TEST_STRING) of nat1
    for i : 1 .. length (TEST_STRING)
        data (i) := ord (TEST_STRING (i))
    end for
    
    % Keep track of recieve interval
    var recvStart : int := Time.Elapsed
    
    loop
        exit when not shouldRun
        
        % Send test data
        var sendStart : int := Time.Elapsed
        len := arb -> writePacket (connID, data)
        sendTime += Time.Elapsed - sendStart
        sendCycles += 1
    
        %% Receive the echo'd data %%
        if arb -> poll() then
            loop
                var packet : ^Packet := arb -> getPacket ()
                exit when packet = nil or not shouldRun
                
                % Put the data onto the screen
                locate (8, 1)
                put "A", ourPort - 7007,   " " ..
                put "C", packet -> connID, " "..
                put "S", packet -> size,   " "..
                
                for i : 0 .. packet -> size - 1
                    put char @ (packet -> getPayload () + i) ..
                end for
                
                recvCounter += 1
                
                % Stop once there's no more packets to process
                exit when not arb -> nextPacket ()
            end loop
            
            % Update statistics
            recvPeriod := Time.Elapsed - recvStart
            recvTime += recvPeriod
            recvCycles += 1
            recvStart := Time.Elapsed
        end if
        
        % Print statistics
        locate (maxrow - 2, 1)
        put "Period: ", recvPeriod, " ms"
        
        if sendCycles > 0 then
            locate (maxrow - 1, 1)
            var frq : real := (sendTime / sendCycles)
            
            if frq > 0 then
                put "Send: ", 1000 / frq, " p/s"
            end if
        end if
        
        if recvCycles > 0 then
            locate (maxrow - 0, 1)
            var period : real := (recvTime / recvCycles)
            
            put "AvgPeriod: ", period, " ms" ..
        end if
        
        if Time.Elapsed - recvTimer > 1000 then
            locate (maxrow - 0, maxcol div 2)
            put "RecvAmt: ", recvCounter ..
            
            recvCounter := 0
            recvTimer := Time.Elapsed
        end if
        
        Time.DelaySinceLast (2)
    end loop
    
    %% Disconnect %%
    put "Disconnecting remote arbiter #", connID
    arb -> disconnect (connID)
    
    %% Stop the arbiter %%
    cleanUp (arb)
    delay (1000)
    Window.Close (winID)
end EndpointTest

process ListenerTest ()
    var ourPort : int := 8888
    var arb : ^ Arbiter
    var winID : int := Window.Open ("graphics;title:Arbiter Listener;position:center;bottom")
    Window.Select (winID)
    
    new Arbiter, arb
    
    % Connect to the arbiter
    put "Starting arbiter listener"
    arb -> startup (ourPort, 8087)
    if arb -> getError () not= 0 then
        put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
        cleanUp (arb)
        
        delay (1000)
        Window.Close (winID)
        shouldRun := false
        return
    end if
    
    loop
        exit when not shouldRun
        
        % Check for incoming data
        if arb -> poll() then
            % Process the status updates first
            loop
                var status : ^ConnectionStatus := arb -> getStatus()
                exit when status = nil or not shouldRun
                
                locate (12 + status -> connID, 1)
                case status -> statusType of
                label STATUS_NEW:           put "New connection (#", status -> connID, ")"
                label STATUS_DISCONNECT:    put "Connection closed (#", status -> connID, ")"
                label:      put "Unknown type (", status -> statusType, ")"
                end case
                
                exit when not arb -> nextStatus()
            end loop
        
            % Process all of the packets
            loop
                var packet : ^Packet := arb -> getPacket ()
                exit when packet = nil or not shouldRun
                
                % Incoming Data
                % Put the data onto the screen
                locate (8 + packet -> connID, 1)
                put packet -> connID, " "..
                put packet -> size, " "..
                
                for i : 0 .. packet -> size - 1
                    put char @ (packet -> getPayload() + i) ..
                end for
                put ""
                
                % Write back some data
                var sendBack : string := ""
                sendBack += "hello, #"
                sendBack += intstr (packet -> connID)
                sendBack += "\n"
                
                % Build the packet data
                var data : array 1 .. length (sendBack) of nat1
                for i : 1 .. upper (data)
                    data(i) := ord (sendBack (i))
                end for
                
                var dmy := arb -> writePacket (packet -> connID, data)
                
                exit when not arb -> nextPacket ()
            end loop
        end if
    end loop
    
    %% Stop the arbiter %%
    cleanUp (arb)
    delay (1000)
    Window.Close (winID)
end ListenerTest


new portLock

put "hai"
fork ListenerTest ()
put "hop"
delay (2000)
put "bam"
for i : 1 .. 2
    fork EndpointTest ()
end for

loop
    exit when not shouldRun
    shouldRun := not Input.hasch ()
    Input.Flush ()
end loop

free portLock