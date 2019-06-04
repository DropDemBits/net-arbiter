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
    
    new Arbiter, arb
    
    % Get usage port
    portLock -> acquire ()
    ourPort := basePort
    basePort += 1
    portLock -> release ()
    
    winID := Window.Open ("graphics;title:Arbiter " + intstr(ourPort - 7007))
    Window.Select (winID)
    
    put "Starting arbiter"
    arb -> startup (ourPort, 0)
    if arb -> getError () not= 0 then
        put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
        cleanUp (arb)
        return
    end if
    
    %% Connect to the remote arbiter %%
    put "Connecting to a remote arbiter"
    connID := arb -> connectTo ("localhost", 8087)
    
    if connID >= 0 then
        put "Connected to remote arbiter #", connID
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
    
    loop
        exit when not shouldRun
        
        len := arb -> writePacket (connID, data)
        len := arb -> writePacket (connID, data)
        
        %% Receive the echo'd data %%
        var recvStart : int := Time.Elapsed
        loop
            exit when arb -> poll()
        end loop
        %put "Sent -> Recv: ", Time.Elapsed - recvStart
        
        loop
            var packet : ^Packet := arb -> getPacket ()
            exit when packet = nil or not shouldRun
            
            if packet -> size > 0 then
                % Put the data onto the screen
                /*put ourPort - 7007, " " ..
                put packet -> connID, " "..
                put packet -> size, " "..
                
                for i : 0 .. packet -> size - 1
                    put char @ (packet -> bytes + i) ..
                end for
                put ""*/
            end if
            
            exit when not arb -> nextPacket ()
        end loop
        
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
    var ourPort : int := 6060
    var arb : ^ Arbiter
    var winID : int := Window.Open ("graphics;title:Arbiter Listener")
    Window.Select (winID)
    
    new Arbiter, arb
    
    % Connect to the arbiter
    put "Starting arbiter listener"
    arb -> startup (ourPort, 8087)
    if arb -> getError () not= 0 then
        put "Error occured (", NetArbiter.errorToString (arb -> getError ()), ")"
        cleanUp (arb)
        return
    end if
    
    loop
        exit when not shouldRun
        
        % Check for incoming data
        if arb -> poll() then
            % Process all of the packets
            loop
                var packet : ^Packet := arb -> getPacket ()
                exit when packet = nil or not shouldRun
                
                if packet -> size > 0 then
                    % Incoming Data
                    % Put the data onto the screen
                    put packet -> connID, " "..
                    put packet -> size, " "..
                    
                    for i : 0 .. packet -> size - 1
                        put char @ (packet -> bytes + i) ..
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
                else
                    % Special: New Connection or Remote Disconnect
                    var specialType : char := chr (cheat (nat1, packet -> bytes))
                    
                    case specialType of
                    label 'N':  put "New connection (#", packet -> connID, ")"
                    label 'R':  put "Connection closed (#", packet -> connID, ")"
                    label:      put "Unknown type (", specialType, ")"
                    end case
                end if
                
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
delay (1000)
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