/******************************************************************************
 *
 * Copyright (c) 2016, Cormac Flanagan (University of California, Santa Cruz) and Stephen Freund
 * (Williams College)
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions
 * and the following disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the names of the University of California, Santa Cruz and Williams College nor the names
 * of its contributors may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 ******************************************************************************/

package tools.aerodrome;

import acme.util.Assert;
import acme.util.Util;
import acme.util.count.AggregateCounter;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DecorationFactory.Type;
import acme.util.decorations.DefaultValue;
import acme.util.decorations.NullDefault;
import acme.util.io.XMLWriter;
import acme.util.option.CommandLine;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import rr.RRMain;
import rr.annotations.Abbrev;
import rr.barrier.BarrierEvent;
import rr.barrier.BarrierListener;
import rr.barrier.BarrierMonitor;
import rr.error.ErrorMessage;
import rr.error.ErrorMessages;
import rr.event.AccessEvent;
import rr.event.AccessEvent.Kind;
import rr.event.AcquireEvent;
import rr.event.ArrayAccessEvent;
import rr.event.ClassAccessedEvent;
import rr.event.ClassInitializedEvent;
import rr.event.FieldAccessEvent;
import rr.event.JoinEvent;
import rr.event.NewThreadEvent;
import rr.event.ReleaseEvent;
import rr.event.StartEvent;
import rr.event.VolatileAccessEvent;
import rr.event.WaitEvent;
import rr.instrument.classes.ArrayAllocSiteTracker;
import rr.meta.AccessInfo;
import rr.meta.ArrayAccessInfo;
import rr.meta.ClassInfo;
import rr.meta.FieldInfo;
import rr.meta.MetaDataInfoMaps;
import rr.meta.MethodInfo;
import rr.meta.OperationInfo;
import rr.meta.SourceLocation;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.util.Epoch;
import tools.util.VectorClock;

/*
 * A revised FastTrack Tool. This makes several improvements over the original: - Simpler
 * synchronization scheme for VarStates. (The old optimistic scheme no longer has a performance
 * benefit and was hard to get right.) - Rephrased rules to: - include a Read-Shared-Same-Epoch
 * test. - eliminate an unnecessary update on joins (this was just for the proof). - remove the
 * Read-Shared to Exclusive transition. The last change makes the correctness argument easier and
 * that transition had little to no performance impact in practice. - Properly replays events when
 * the fast paths detect an error in all cases. - Supports long epochs for larger clock values. -
 * Handles tid reuse more precisely. The performance over the JavaGrande and DaCapo benchmarks is
 * more or less identical to the old implementation (within ~1% overall in our tests).
 */
@Abbrev("AD")
public class AerodromeTool extends Tool implements BarrierListener<FTBarrierState> {

    private static final boolean COUNT_OPERATIONS = RRMain.slowMode();
    private static final int INIT_VECTOR_CLOCK_SIZE = 4;

    public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages
            .makeFieldErrorMessage("FastTrack");
    public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages
            .makeArrayErrorMessage("FastTrack");

    private final VectorClock maxEpochPerTid = new VectorClock(INIT_VECTOR_CLOCK_SIZE);

    public ConcurrentHashMap <ShadowLock, ShadowThread> lastThreadToRelease;
    public ConcurrentHashMap <ShadowVar, ShadowThread> lastThreadToWrite;
    public ConcurrentHashMap <ShadowThread, Integer> threadToNestingDepth;

    public static class TransactionHandling {
        private static String filename;
        // This map contains the race pairs provided in the input file.
        private static ConcurrentHashMap<String, String> transactionLocations = new ConcurrentHashMap<String, String>();
        public TransactionHandling() {
            filename = rr.RRMain.transactionFileOption.get();
        }
        public void transactionBegin(){

        }
        public void transactionEnd(){

        }
        public void CheckLocation(String sloc) {
            if(transactionLocations.containsKey(sloc)) {
                transactionBegin();
            } else if (transactionLocations.containsValue(sloc)) {
                transactionEnd();
            }
        }
        public void ReadFile() {
            try{
                File file = new File(filename);
                FileReader fr = new FileReader(file);
                BufferedReader br = new BufferedReader(fr);
                String pairline;
                while((pairline = br.readLine()) != null) {
                    String[] pair = pairline.split(",");
                    transactionLocations.put(pair[0],pair[1]);
                }
            } catch(IOException e) { e.printStackTrace(); }
        }
    }

    // CS636: Every class object would have a vector clock. classInitTime is the Decoration which
    // stores ClassInfo (as a key) and corresponding vector clock for that class (as a value).
    // guarded by classInitTime
    public static final Decoration<ClassInfo, VectorClock> classInitTime = MetaDataInfoMaps
            .getClasses().makeDecoration("FastTrack:ClassInitTime", Type.MULTIPLE,
                    new DefaultValue<ClassInfo, VectorClock>() {
                        public VectorClock get(ClassInfo st) {
                            return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
                        }
                    });

    public static VectorClock getClassInitTime(ClassInfo ci) {
        synchronized (classInitTime) {
            return classInitTime.get(ci);
        }
    }
    TransactionHandling th;
    public AerodromeTool(final String name, final Tool next, CommandLine commandLine) {
        super(name, next, commandLine);
        th = new TransactionHandling();
        th.ReadFile();
        new BarrierMonitor<FTBarrierState>(this, new DefaultValue<Object, FTBarrierState>() {
            public FTBarrierState get(Object k) {
                return new FTBarrierState(k, INIT_VECTOR_CLOCK_SIZE);
            }
        });
    }


    //CS636
    protected static VectorClock ts_get_clockThread(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    protected static void ts_set_clockThread(ShadowThread st, VectorClock V) {
        Assert.panic("Bad");
    }

    protected static VectorClock ts_get_clockThreadBegin(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    protected static void ts_set_clockThreadBegin(ShadowThread st, VectorClock V) {
        Assert.panic("Bad");
    }

    //Attach a VectorClock to each object used as a lock.
    public static final Decoration<ShadowLock, VectorClock> shadowLock = ShadowLock.makeDecoration("AE:clockLock",
                    DecorationFactory.Type.MULTIPLE, new DefaultValue<ShadowLock, VectorClock>() {
                        public VectorClock get(ShadowLock ld) {
                            return new VectorClock(1);
                        }
                    });

    @Override
    public ShadowVar makeShadowVar(final AccessEvent event) {
          if (event.getKind() == Kind.VOLATILE) {
                //TODO: how to handle volatile events?
                /*final ShadowThread st = event.getThread();
                final VectorClock volV = getV(((VolatileAccessEvent) event).getShadowVolatile());
                volV.max(ts_get_V(st));*/
                return super.makeShadowVar(event);
          } else {
                return new FTVarState(event.isWrite(), ts_get_E(event.getThread()));
                  }
          }

    @Override
    public void create(NewThreadEvent event) {

        super.create(event);
    }

    @Override
    public void acquire(final AcquireEvent event) {

        super.acquire(event);
    }

    @Override
    public void release(final ReleaseEvent event) {
        super.release(event);
    }

    @Override
    public void access(final AccessEvent event) {

    }

    protected void read(final AccessEvent event, final ShadowThread st, final FTVarState sx) {

    }

    @Override
    public void volatileAccess(final VolatileAccessEvent event) {
    }

    // st forked su
    @Override
    public void preStart(final StartEvent event) {
    }

    @Override
    public void stop(ShadowThread st) {
    }

    // t joined on u
    @Override
    public void postJoin(final JoinEvent event) {
    }

    @Override
    public void preWait(WaitEvent event) {

    }

    @Override
    public void postWait(WaitEvent event) {

    }

    public static String toString(final ShadowThread td) {
      return "test";
    }

    private final Decoration<ShadowThread, VectorClock> vectorClockForBarrierEntry = ShadowThread
            .makeDecoration("FT:barrier", DecorationFactory.Type.MULTIPLE,
                    new NullDefault<ShadowThread, VectorClock>());

    public void preDoBarrier(BarrierEvent<FTBarrierState> event) {
    }

    public void postDoBarrier(BarrierEvent<FTBarrierState> event) {
    }

    ///

    @Override
    public void classInitialized(ClassInitializedEvent event) {

    }

    @Override
    public void classAccessed(ClassAccessedEvent event) {

    }

    @Override
    public void printXML(XMLWriter xml) {
        for (ShadowThread td : ShadowThread.getThreads()) {
            xml.print("thread", toString(td));
        }
    }

    protected void error(final AccessEvent ae, final FTVarState x, final String description,
            final String prevOp, final int prevTid, final String curOp, final int curTid) {

        if (ae instanceof FieldAccessEvent) {
            fieldError((FieldAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
        } else {
            arrayError((ArrayAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
        }
    }

    protected void arrayError(final ArrayAccessEvent aae, final FTVarState sx,
            final String description, final String prevOp, final int prevTid, final String curOp,
            final int curTid) {
        final ShadowThread st = aae.getThread();
        final Object target = aae.getTarget();

        if (arrayErrors.stillLooking(aae.getInfo())) {
            arrayErrors.error(st, aae.getInfo(), "Alloc Site", ArrayAllocSiteTracker.get(target),
                    "Shadow State", sx, "Current Thread", toString(st), "Array",
                    Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]", "Message",
                    description, "Previous Op", prevOp + " " + ShadowThread.get(prevTid),
                    "Currrent Op", curOp + " " + ShadowThread.get(curTid), "Stack",
                    ShadowThread.stackDumpForErrorMessage(st));
        }
        Assert.assertTrue(prevTid != curTid);

        aae.getArrayState().specialize();

        if (!arrayErrors.stillLooking(aae.getInfo())) {
            advance(aae);
        }
    }

    protected void fieldError(final FieldAccessEvent fae, final FTVarState sx,
            final String description, final String prevOp, final int prevTid, final String curOp,
            final int curTid) {
        final FieldInfo fd = fae.getInfo().getField();
        final ShadowThread st = fae.getThread();
        final Object target = fae.getTarget();

        if (fieldErrors.stillLooking(fd)) {
            fieldErrors.error(st, fd, "Shadow State", sx, "Current Thread", toString(st), "Class",
                    (target == null ? fd.getOwner() : target.getClass()), "Field",
                    Util.objectToIdentityString(target) + "." + fd, "Message", description,
                    "Previous Op", prevOp + " " + ShadowThread.get(prevTid), "Currrent Op",
                    curOp + " " + ShadowThread.get(curTid), "Stack",
                    ShadowThread.stackDumpForErrorMessage(st));
        }

        Assert.assertTrue(prevTid != curTid);

        if (!fieldErrors.stillLooking(fd)) {
            advance(fae);
        }
    }
}
