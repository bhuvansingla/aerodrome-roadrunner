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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
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
import rr.event.MethodEvent;
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
// import tools.util.VectorClock;
import tools.aerodrome.AEVectorClock;

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

    private final AEVectorClock maxEpochPerTid = new AEVectorClock(INIT_VECTOR_CLOCK_SIZE);

    public ConcurrentHashMap <ShadowThread, Integer> threadToIndex;
    public ConcurrentHashMap <ShadowLock, Integer> lockToIndex;
    public ConcurrentHashMap <AEVarClocks, Integer> variableToIndex;
    private int numThreads;
    private int numLocks;
    private int numVariables;

    public ConcurrentHashMap <ShadowLock, ShadowThread> lastThreadToRelease;
    public ConcurrentHashMap <ShadowVar, ShadowThread> lastThreadToWrite;
    public ConcurrentHashMap <ShadowThread, Integer> threadToNestingDepth;

    public static class TransactionHandling {
        private static String locationPairFilename;
        private static String methodExcludeFilename;
        // This map contains the race pairs provided in the input file.
        private static ConcurrentHashMap<String, String> transactionLocations = new ConcurrentHashMap<String, String>();

        // This list contains the method names to exclude.
        private static List<String> methodsToExclude = new ArrayList<String>();

        public TransactionHandling() {
            locationPairFilename = rr.RRMain.locationPairFileOption.get();
            methodExcludeFilename = rr.RRMain.methodExcludeFileOption.get();
        }
        public void transactionBegin(){

        }
        public void transactionEnd(){

        }

        public void checkMethod(MethodEvent me) {
            if(!methodsToExclude.contains(me.getInfo().toString())){
                if(me.isEnter()) {
                    System.out.println("Entering: " + me.getInfo().toString());
                    transactionBegin();
                }
                else {
                    System.out.println("Exiting: " + me.getInfo().toString());
                    transactionEnd();
                }
            }
            else {
                System.out.println("Excluding: " + me.getInfo().toString());
            }
        }

        public void CheckLocation(String sloc) {
            if(transactionLocations.containsKey(sloc)) {
                transactionBegin();
            } else if (transactionLocations.containsValue(sloc)) {
                transactionEnd();
            }
        }
        public void ReadLocationPairFile() {
            try{
                File file = new File(locationPairFilename);
                FileReader fr = new FileReader(file);
                BufferedReader br = new BufferedReader(fr);
                String pairline;
                while((pairline = br.readLine()) != null) {
                    String[] pair = pairline.split(",");
                    transactionLocations.put(pair[0],pair[1]);
                }
            } catch(IOException e) { e.printStackTrace(); }
        }

        public void ReadMethodExcludeFile() {
            try{
                File file = new File(methodExcludeFilename);
                FileReader fr = new FileReader(file);
                BufferedReader br = new BufferedReader(fr);
                String line;
                while((line = br.readLine()) != null) {
                    methodsToExclude.add(line);
                }
            } catch(IOException e) { e.printStackTrace(); }
        }
    }

    // CS636: Every class object would have a vector clock. classInitTime is the Decoration which
    // stores ClassInfo (as a key) and corresponding vector clock for that class (as a value).
    // guarded by classInitTime
    public static final Decoration<ClassInfo, AEVectorClock> classInitTime = MetaDataInfoMaps
            .getClasses().makeDecoration("FastTrack:ClassInitTime", Type.MULTIPLE,
                    new DefaultValue<ClassInfo, AEVectorClock>() {
                        public AEVectorClock get(ClassInfo st) {
                            return new AEVectorClock(INIT_VECTOR_CLOCK_SIZE);
                        }
                    });

    public static AEVectorClock getClassInitTime(ClassInfo ci) {
        synchronized (classInitTime) {
            return classInitTime.get(ci);
        }
    }
    TransactionHandling th;
    public AerodromeTool(final String name, final Tool next, CommandLine commandLine) {
        super(name, next, commandLine);
        th = new TransactionHandling();
        numThreads = 0;
        threadToIndex = new ConcurrentHashMap<ShadowThread, Integer>();
        numLocks = 0;
        lockToIndex = new ConcurrentHashMap<ShadowLock, Integer>();
        numVariables = 0;
        variableToIndex = new ConcurrentHashMap<AEVarClocks, Integer>();

        lastThreadToRelease = new ConcurrentHashMap<ShadowLock, ShadowThread>();
        lastThreadToWrite = new ConcurrentHashMap<ShadowVar, ShadowThread>();
        threadToNestingDepth = new ConcurrentHashMap<ShadowThread, Integer>();

        th.ReadLocationPairFile();
        th.ReadMethodExcludeFile();
        new BarrierMonitor<FTBarrierState>(this, new DefaultValue<Object, FTBarrierState>() {
            public FTBarrierState get(Object k) {
                return new FTBarrierState(k, INIT_VECTOR_CLOCK_SIZE);
            }
        });
    }

    // /*
    //  * Shadow State: St.E -- epoch decoration on ShadowThread - Thread-local. Never access from a
    //  * different thread St.V -- VectorClock decoration on ShadowThread - Thread-local while thread
    //  * is running. - The thread starting t may access st.V before the start. - Any thread joining on
    //  * t may read st.V after the join. Sm.V -- FTLockState decoration on ShadowLock - See
    //  * FTLockState for synchronization rules. Sx.R,Sx.W,Sx.V -- FTVarState objects - See FTVarState
    //  * for synchronization rules. Svx.V -- FTVolatileState decoration on ShadowVolatile (serves same
    //  * purpose as L for volatiles) - See FTVolatileState for synchronization rules. Sb.V --
    //  * FTBarrierState decoration on Barriers - See FTBarrierState for synchronization rules.
    //  */

    // // invariant: st.E == st.V(st.tid)
    // protected static int/* epoch */ ts_get_E(ShadowThread st) {
    //     Assert.panic("Bad");
    //     return -1;
    // }

    // protected static void ts_set_E(ShadowThread st, int/* epoch */ e) {
    //     Assert.panic("Bad");
    // }

    // protected static VectorClock ts_get_V(ShadowThread st) {
    //     Assert.panic("Bad");
    //     return null;
    // }

    // protected static void ts_set_V(ShadowThread st, VectorClock V) {
    //     Assert.panic("Bad");
    // }

    // protected void maxAndIncEpochAndCV(ShadowThread st, VectorClock other, OperationInfo info) {
    //     final int tid = st.getTid();
    //     final VectorClock tV = ts_get_V(st);
    //     tV.max(other);
    //     tV.tick(tid);
    //     ts_set_E(st, tV.get(tid));
    // }

    // protected void maxEpochAndCV(ShadowThread st, VectorClock other, OperationInfo info) {
    //     final int tid = st.getTid();
    //     final VectorClock tV = ts_get_V(st);
    //     tV.max(other);
    //     ts_set_E(st, tV.get(tid));
    // }

    // protected void incEpochAndCV(ShadowThread st, OperationInfo info) {
    //     final int tid = st.getTid();
    //     final VectorClock tV = ts_get_V(st);
    //     tV.tick(tid);
    //     ts_set_E(st, tV.get(tid));
    // }

    // static final Decoration<ShadowLock, FTLockState> lockVs = ShadowLock.makeDecoration(
    //         "FastTrack:ShadowLock", DecorationFactory.Type.MULTIPLE,
    //         new DefaultValue<ShadowLock, FTLockState>() {
    //             public FTLockState get(final ShadowLock lock) {
    //                 return new FTLockState(lock, INIT_VECTOR_CLOCK_SIZE);
    //             }
    //         });

    // // only call when ld.peer() is held
    // static final FTLockState getV(final ShadowLock ld) {
    //     return lockVs.get(ld);
    // }

    // static final Decoration<ShadowVolatile, FTVolatileState> volatileVs = ShadowVolatile
    //         .makeDecoration("FastTrack:shadowVolatile", DecorationFactory.Type.MULTIPLE,
    //                 new DefaultValue<ShadowVolatile, FTVolatileState>() {
    //                     public FTVolatileState get(final ShadowVolatile vol) {
    //                         return new FTVolatileState(vol, INIT_VECTOR_CLOCK_SIZE);
    //                     }
    //                 });

    // // only call when we are in an event handler for the volatile field.
    // protected static final FTVolatileState getV(final ShadowVolatile ld) {
    //     return volatileVs.get(ld);
    // }

    
    //CS636
    protected static AEVectorClock ts_get_clockThread(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    protected static void ts_set_clockThread(ShadowThread st, AEVectorClock V) {
        Assert.panic("Bad");
    }

    protected static AEVectorClock ts_get_clockThreadBegin(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    protected static void ts_set_clockThreadBegin(ShadowThread st, AEVectorClock V) {
        Assert.panic("Bad");
    }

    //Attach a AEVectorClock to each object used as a lock.
    public static final Decoration<ShadowLock, AEVectorClock> clockLock = ShadowLock.makeDecoration("AE:clockLock",
    DecorationFactory.Type.MULTIPLE, new DefaultValue<ShadowLock, AEVectorClock>() {
        public AEVectorClock get(ShadowLock ld) {
            return new AEVectorClock(INIT_VECTOR_CLOCK_SIZE);
        }
    });

    // @Override
    // public ShadowVar makeShadowVar(final AccessEvent event) {
    //     if (event.getKind() == Kind.VOLATILE) {
    //         final ShadowThread st = event.getThread();
    //         final VectorClock volV = getV(((VolatileAccessEvent) event).getShadowVolatile());
    //         volV.max(ts_get_V(st));
    //         return super.makeShadowVar(event);
    //     } else {
    //         return new FTVarState(event.isWrite(), ts_get_E(event.getThread()));
    //     }
    // }

    @Override
    public ShadowVar makeShadowVar(final AccessEvent event) {
        if (event.getKind() == Kind.VOLATILE) {
            //TODO: how to handle volatile events?
            /*final ShadowThread st = event.getThread();
            final VectorClock volV = getV(((VolatileAccessEvent) event).getShadowVolatile());
            volV.max(ts_get_V(st));*/
            return super.makeShadowVar(event);
        } else {

            AEVarClocks vcs = new AEVarClocks(INIT_VECTOR_CLOCK_SIZE);

            if(!variableToIndex.containsKey(vcs)){
                variableToIndex.put(vcs, numVariables);
                numVariables ++;
            }

            return vcs;
        }
    }

    @Override
    public void create(NewThreadEvent event) {
        
        final ShadowThread st = event.getThread();
        threadToIndex.put(st, (Integer)numThreads);
        numThreads++;

        threadToNestingDepth.put(st, 0);

        if (ts_get_clockThread(st) == null) {
            final AEVectorClock tV = new AEVectorClock(INIT_VECTOR_CLOCK_SIZE);
            ts_set_clockThread(st, tV);
        }

        ts_get_clockThread(st).setClockIndex(st.getTid(), 1);

        if (ts_get_clockThreadBegin(st) == null) {
            final AEVectorClock tV = new AEVectorClock(INIT_VECTOR_CLOCK_SIZE);
            ts_set_clockThreadBegin(st, tV);
        }

        // if (ts_get_V(st) == null) {
        //     final int tid = st.getTid();
        //     final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
        //     ts_set_V(st, tV);
        //     synchronized (maxEpochPerTid) {
        //         final int/* epoch */ epoch = maxEpochPerTid.get(tid) + 1;
        //         tV.set(tid, epoch);
        //         ts_set_E(st, epoch);
        //     }
        //     incEpochAndCV(st, null);
        //     Util.log("Initial E for " + tid + ": " + Epoch.toString(ts_get_E(st)));
        // }

        super.create(event);
    }

    @Override
    public void acquire(final AcquireEvent event) {
        boolean violationDetected = false;
        final ShadowThread st = event.getThread();
        ShadowLock sl = event.getLock();

        checkAndAddShadowLock(sl);
		AEVectorClock L_l = clockLock.get(sl);

		if(lastThreadToRelease.containsKey(sl)) {
			if(!lastThreadToRelease.get(sl).equals(st)) {
				violationDetected = checkAndGetClock(L_l, L_l, st);
			}
		}
        super.acquire(event);
		// return violationDetected; 
        // TODO : ERROR DETECTED 
        // if (COUNT_OPERATIONS)
        //     acquire.inc(st.getTid());
    }

    public int checkAndAddShadowLock(ShadowLock sl){
		if(!lockToIndex.containsKey(sl)){
			lockToIndex.put(sl, this.numLocks);
			this.numLocks ++;
			clockLock.set(sl, new AEVectorClock(INIT_VECTOR_CLOCK_SIZE));
		}
		return lockToIndex.get(sl);
	}

    public boolean checkAndGetClock(AEVectorClock checkClock, AEVectorClock fromClock, ShadowThread target) {
		int tIndex = threadToIndex.get(target);
		boolean violationDetected = false;
		AEVectorClock C_target_begin = ts_get_clockThreadBegin(target);
		if(C_target_begin.isLessThanOrEqual(checkClock, tIndex) && threadToNestingDepth.get(target) > 0) {
			violationDetected = true;
		}
		AEVectorClock C_target = ts_get_clockThread(target);
		C_target.updateWithMax(C_target, fromClock);
		return violationDetected;
	}

    @Override
    public void release(final ReleaseEvent event) {

        final ShadowThread st = event.getThread();
        ShadowLock sl = event.getLock();
		checkAndAddShadowLock(sl);
		AEVectorClock C_t = ts_get_clockThread(st);
		AEVectorClock L_l = clockLock.get(sl);

		L_l.copyFrom(C_t);
		lastThreadToRelease.put(sl, st);
		if(threadToNestingDepth.get(st) == 0) {
			incClockThread(st);
		}
        super.release(event);
        // if (COUNT_OPERATIONS)
        //     release.inc(st.getTid());
    }

    public void incClockThread(ShadowThread st) {
		int tIndex = threadToIndex.get(st);
		int origVal =  ts_get_clockThread(st).getClockIndex(tIndex);
		ts_get_clockThread(st).setClockIndex(tIndex, (Integer)(origVal + 1));
	}

    static FTVarState ts_get_badVarState(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    static void ts_set_badVarState(ShadowThread st, FTVarState v) {
        Assert.panic("Bad");
    }

    // protected static ShadowVar getOriginalOrBad(ShadowVar original, ShadowThread st) {
    //     // final FTVarState savedState = ts_get_badVarState(st);
    //     // if (savedState != null) {
    //     //     ts_set_badVarState(st, null);
    //     //     return savedState;
    //     // } else {
    //     //     return original;
    //     // }
    //     return original;
    // }

    @Override
    public void enter(MethodEvent me) {
        // System.out.println("Entering: " + me.toString());
        th.checkMethod(me);
        super.enter(me);
    }
    
    @Override
    public void exit(MethodEvent me) {
        // System.out.println("Exiting: " + me.toString());
        th.checkMethod(me);
        super.exit(me);
    }

    @Override
    public void access(final AccessEvent event) {
        SourceLocation sl = event.getAccessInfo().getLoc();
		
        ShadowVar sv = event.getOriginalShadow();
        if (sv instanceof AEVarClocks) {
            AEVarClocks sx = (AEVarClocks) sv;

            // If source location pair is given, then this call is needed
            // for starting and ending transaction
            // th.CheckLocation(sl.toString());
            if (event.isWrite()) {
                write(event, st, sx);
            } else {
                read(event, st, sx);
            }
        } else {
            super.access(event);
        }
    }

    // // Counters for relative frequencies of each rule
    // private static final ThreadLocalCounter readSameEpoch = new ThreadLocalCounter("FT",
    //         "Read Same Epoch", RR.maxTidOption.get());
    // private static final ThreadLocalCounter readSharedSameEpoch = new ThreadLocalCounter("FT",
    //         "ReadShared Same Epoch", RR.maxTidOption.get());
    // private static final ThreadLocalCounter readExclusive = new ThreadLocalCounter("FT",
    //         "Read Exclusive", RR.maxTidOption.get());
    // private static final ThreadLocalCounter readShare = new ThreadLocalCounter("FT", "Read Share",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter readShared = new ThreadLocalCounter("FT", "Read Shared",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter writeReadError = new ThreadLocalCounter("FT",
    //         "Write-Read Error", RR.maxTidOption.get());
    // private static final ThreadLocalCounter writeSameEpoch = new ThreadLocalCounter("FT",
    //         "Write Same Epoch", RR.maxTidOption.get());
    // private static final ThreadLocalCounter writeExclusive = new ThreadLocalCounter("FT",
    //         "Write Exclusive", RR.maxTidOption.get());
    // private static final ThreadLocalCounter writeShared = new ThreadLocalCounter("FT",
    //         "Write Shared", RR.maxTidOption.get());
    // private static final ThreadLocalCounter writeWriteError = new ThreadLocalCounter("FT",
    //         "Write-Write Error", RR.maxTidOption.get());
    // private static final ThreadLocalCounter readWriteError = new ThreadLocalCounter("FT",
    //         "Read-Write Error", RR.maxTidOption.get());
    // private static final ThreadLocalCounter sharedWriteError = new ThreadLocalCounter("FT",
    //         "Shared-Write Error", RR.maxTidOption.get());
    // private static final ThreadLocalCounter acquire = new ThreadLocalCounter("FT", "Acquire",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter release = new ThreadLocalCounter("FT", "Release",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter fork = new ThreadLocalCounter("FT", "Fork",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter join = new ThreadLocalCounter("FT", "Join",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter barrier = new ThreadLocalCounter("FT", "Barrier",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter wait = new ThreadLocalCounter("FT", "Wait",
    //         RR.maxTidOption.get());
    // private static final ThreadLocalCounter vol = new ThreadLocalCounter("FT", "Volatile",
    //         RR.maxTidOption.get());

    // private static final ThreadLocalCounter other = new ThreadLocalCounter("FT", "Other",
    //         RR.maxTidOption.get());

    // static {
    //     AggregateCounter reads = new AggregateCounter("FT", "Total Reads", readSameEpoch,
    //             readSharedSameEpoch, readExclusive, readShare, readShared, writeReadError);
    //     AggregateCounter writes = new AggregateCounter("FT", "Total Writes", writeSameEpoch,
    //             writeExclusive, writeShared, writeWriteError, readWriteError, sharedWriteError);
    //     AggregateCounter accesses = new AggregateCounter("FT", "Total Access Ops", reads, writes);
    //     new AggregateCounter("FT", "Total Ops", accesses, acquire, release, fork, join, barrier,
    //             wait, vol, other);
    // }

    protected void read(final AccessEvent event, final ShadowThread st, final AEVarClocks vcs) {

        boolean violationDetected = false;	
		ShadowThread st = event.getThread();
		// ShadowVar sv = event.getOriginalShadow();
        // checkAndAddShadowVariable(sv);
		AEVectorClock C_t = ts_get_clockThread(st);
		AEVectorClock W_v = vcs.write;

		if(lastThreadToWrite.containsKey(vcs)) {
			if(!lastThreadToWrite.get(vcs).equals(st)) {
				violationDetected |= checkAndGetClock(W_v, W_v, st);
			}
		}
		AEVectorClock R_v = vcs.read;
		R_v.updateWithMax(R_v, C_t);
		AEVectorClock chR_v = vcs.readcheck;
        chR_v.updateMax2WithoutLocal(C_t, threadToIndex.get(st));
		if(threadToNestingDepth.get(st) == 0) {
			incClockThread(t);
		}
		// return violationDetected; // TODO : Handle Error
    }

    // public int checkAndAddShadowVariable(AEVarClocks vcs){
	// 	if(!variableToIndex.containsKey(vcs)){
	// 		variableToIndex.put(vcs, this.numVariables);
	// 		this.numVariables ++;
    //         // vc.rd.put(new VectorClockOpt(INIT_VECTOR_CLOCK_SIZE));
    //         // this.clockReadVariableCheck.put(v, new VectorClockOpt(INIT_VECTOR_CLOCK_SIZE));
    //         // this.clockWriteVariable.put(v, new VectorClockOpt(INIT_VECTOR_CLOCK_SIZE));
	// 	}
	// 	return variableToIndex.get(v);
	// }

    // CS636: Commented the method to prevent inlining of the read barrier
    // public static boolean readFastPath(final ShadowVar shadow, final ShadowThread st) {
    // if (shadow instanceof FTVarState) {
    // final FTVarState sx = ((FTVarState) shadow);

    // final int/* epoch */ e = ts_get_E(st);

    // /* optional */ {
    // final int/* epoch */ r = sx.R;
    // if (r == e) {
    // if (COUNT_OPERATIONS)
    // readSameEpoch.inc(st.getTid());
    // return true;
    // } else if (r == Epoch.READ_SHARED && sx.get(st.getTid()) == e) {
    // if (COUNT_OPERATIONS)
    // readSharedSameEpoch.inc(st.getTid());
    // return true;
    // }
    // }

    // synchronized (sx) {
    // final int tid = st.getTid();
    // final VectorClock tV = ts_get_V(st);
    // final int/* epoch */ r = sx.R;
    // final int/* epoch */ w = sx.W;
    // final int wTid = Epoch.tid(w);
    // if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }

    // if (r != Epoch.READ_SHARED) {
    // final int rTid = Epoch.tid(r);
    // if (rTid == tid || Epoch.leq(r, tV.get(rTid))) {
    // if (COUNT_OPERATIONS)
    // readExclusive.inc(tid);
    // sx.R = e;
    // } else {
    // if (COUNT_OPERATIONS)
    // readShare.inc(tid);
    // int initSize = Math.max(Math.max(rTid, tid), INIT_VECTOR_CLOCK_SIZE);
    // sx.makeCV(initSize);
    // sx.set(rTid, r);
    // sx.set(tid, e);
    // sx.R = Epoch.READ_SHARED;
    // }
    // } else {
    // if (COUNT_OPERATIONS)
    // readShared.inc(tid);
    // sx.set(tid, e);
    // }
    // return true;
    // }
    // } else {
    // return false;
    // }
    // }

    /***/

    protected void write(final AccessEvent event, final ShadowThread st, final AEVarClocks vc) {
        // final int/* epoch */ e = ts_get_E(st);

        // /* optional */ {
        //     final int/* epoch */ w = sx.W;
        //     if (w == e) {
        //         if (COUNT_OPERATIONS)
        //             writeSameEpoch.inc(st.getTid());
        //         return;
        //     }
        // }

        // synchronized (sx) {
        //     final int/* epoch */ w = sx.W;
        //     final int wTid = Epoch.tid(w);
        //     final int tid = st.getTid();
        //     final VectorClock tV = ts_get_V(st);

        //     if (wTid != tid /* optimization */ && !Epoch.leq(w, tV.get(wTid))) {
        //         if (COUNT_OPERATIONS)
        //             writeWriteError.inc(tid);
        //         error(event, sx, "Write-Write Race", "Write by ", wTid, "Write by ", tid);
        //     }

        //     final int/* epoch */ r = sx.R;
        //     if (r != Epoch.READ_SHARED) {
        //         final int rTid = Epoch.tid(r);
        //         if (rTid != tid /* optimization */ && !Epoch.leq(r, tV.get(rTid))) {
        //             if (COUNT_OPERATIONS)
        //                 readWriteError.inc(tid);
        //             error(event, sx, "Read-Write Race", "Read by ", rTid, "Write by ", tid);
        //         } else {
        //             if (COUNT_OPERATIONS)
        //                 writeExclusive.inc(tid);
        //         }
        //     } else {
        //         if (sx.anyGt(tV)) {
        //             for (int prevReader = sx.nextGt(tV, 0); prevReader > -1; prevReader = sx
        //                     .nextGt(tV, prevReader + 1)) {
        //                 error(event, sx, "Read(Shared)-Write Race", "Read by ", prevReader,
        //                         "Write by ", tid);
        //             }
        //             if (COUNT_OPERATIONS)
        //                 sharedWriteError.inc(tid);
        //         } else {
        //             if (COUNT_OPERATIONS)
        //                 writeShared.inc(tid);
        //         }
        //     }
        //     sx.W = e;
        // }
    }

    // CS636: Commented the method to prevent inlining of the read barrier
    // only count events when returning true;
    // public static boolean writeFastPath(final ShadowVar shadow, final ShadowThread st) {
    // if (shadow instanceof FTVarState) {
    // final FTVarState sx = ((FTVarState) shadow);

    // final int/* epoch */ E = ts_get_E(st);

    // /* optional */ {
    // final int/* epoch */ w = sx.W;
    // if (w == E) {
    // if (COUNT_OPERATIONS)
    // writeSameEpoch.inc(st.getTid());
    // return true;
    // }
    // }

    // synchronized (sx) {
    // final int tid = st.getTid();
    // final int/* epoch */ w = sx.W;
    // final int wTid = Epoch.tid(w);
    // final VectorClock tV = ts_get_V(st);

    // if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }

    // final int/* epoch */ r = sx.R;
    // if (r != Epoch.READ_SHARED) {
    // final int rTid = Epoch.tid(r);
    // if (rTid != tid && !Epoch.leq(r, tV.get(rTid))) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }
    // if (COUNT_OPERATIONS)
    // writeExclusive.inc(tid);
    // } else {
    // if (sx.anyGt(tV)) {
    // ts_set_badVarState(st, sx);
    // return false;
    // }
    // if (COUNT_OPERATIONS)
    // writeShared.inc(tid);
    // }
    // sx.W = E;
    // return true;
    // }
    // } else {
    // return false;
    // }
    // }

    /*****/

    @Override
    public void volatileAccess(final VolatileAccessEvent event) {
        // final ShadowThread st = event.getThread();
        // final VectorClock volV = getV((event).getShadowVolatile());

        // if (event.isWrite()) {
        //     final VectorClock tV = ts_get_V(st);
        //     volV.max(tV);
        //     incEpochAndCV(st, event.getAccessInfo());
        // } else {
        //     maxEpochAndCV(st, volV, event.getAccessInfo());
        // }

        super.volatileAccess(event);
        // if (COUNT_OPERATIONS)
        //     vol.inc(st.getTid());
    }

    // st forked su
    @Override
    public void preStart(final StartEvent event) {
        // final ShadowThread st = event.getThread();
        // final ShadowThread su = event.getNewThread();
        // final VectorClock tV = ts_get_V(st);

        // /*
        //  * Safe to access su.V, because u has not started yet. This will give us exclusive access to
        //  * it. There may be a race if two or more threads race are starting u, but of course, a
        //  * second attempt to start u will crash... RR guarantees that the forked thread will
        //  * synchronize with thread t before it does anything else.
        //  */
        // maxAndIncEpochAndCV(su, tV, event.getInfo());
        // incEpochAndCV(st, event.getInfo());

        super.preStart(event);
        // if (COUNT_OPERATIONS)
        //     fork.inc(st.getTid());
    }

    @Override
    public void stop(ShadowThread st) {
        // synchronized (maxEpochPerTid) {
        //     maxEpochPerTid.set(st.getTid(), ts_get_E(st));
        // }
        super.stop(st);
        // if (COUNT_OPERATIONS)
        //     other.inc(st.getTid());
    }

    // t joined on u
    @Override
    public void postJoin(final JoinEvent event) {
        // final ShadowThread st = event.getThread();
        // final ShadowThread su = event.getJoiningThread();

        // // move our clock ahead. Safe to access su.V, as above, when
        // // lock is held and u is not running. Also, RR guarantees
        // // this thread has sync'd with u.

        // maxEpochAndCV(st, ts_get_V(su), event.getInfo());
        // no need to inc su's clock here -- that was just for
        // the proof in the original FastTrack rules.

        super.postJoin(event);
        // if (COUNT_OPERATIONS)
        //     join.inc(st.getTid());
    }

    @Override
    public void preWait(WaitEvent event) {
        // final ShadowThread st = event.getThread();
        // final VectorClock lockV = getV(event.getLock());
        // lockV.max(ts_get_V(st)); // we hold lock, so no need to sync here...
        // incEpochAndCV(st, event.getInfo());
        super.preWait(event);
        // if (COUNT_OPERATIONS)
        //     wait.inc(st.getTid());
    }

    @Override
    public void postWait(WaitEvent event) {
        // final ShadowThread st = event.getThread();
        // final VectorClock lockV = getV(event.getLock());
        // maxEpochAndCV(st, lockV, event.getInfo()); // we hold lock here
        super.postWait(event);
        // if (COUNT_OPERATIONS)
        //     wait.inc(st.getTid());
    }

    public static String toString(final ShadowThread td) {
        return "You left this empty";
        // return String.format("[tid=%-2d   C=%s   E=%s]", td.getTid(), ts_get_V(td),
        //         Epoch.toString(ts_get_E(td)));
    }

    private final Decoration<ShadowThread, AEVectorClock> vectorClockForBarrierEntry = ShadowThread
            .makeDecoration("FT:barrier", DecorationFactory.Type.MULTIPLE,
                    new NullDefault<ShadowThread, AEVectorClock>());

    public void preDoBarrier(BarrierEvent<FTBarrierState> event) {
        // final ShadowThread st = event.getThread();
        // final FTBarrierState barrierObj = event.getBarrier();
        // synchronized (barrierObj) {
        //     final VectorClock barrierV = barrierObj.enterBarrier();
        //     barrierV.max(ts_get_V(st));
        //     vectorClockForBarrierEntry.set(st, barrierV);
        // }
        // if (COUNT_OPERATIONS)
        //     barrier.inc(st.getTid());
    }

    public void postDoBarrier(BarrierEvent<FTBarrierState> event) {
        // final ShadowThread st = event.getThread();
        // final FTBarrierState barrierObj = event.getBarrier();
        // synchronized (barrierObj) {
        //     final VectorClock barrierV = vectorClockForBarrierEntry.get(st);
        //     barrierObj.stopUsingOldVectorClock(barrierV);
        //     maxAndIncEpochAndCV(st, barrierV, null);
        // }
        // if (COUNT_OPERATIONS)
        //     barrier.inc(st.getTid());
    }

    ///

    @Override
    public void classInitialized(ClassInitializedEvent event) {
        // final ShadowThread st = event.getThread();
        // final VectorClock tV = ts_get_V(st);
        // synchronized (classInitTime) {
        //     VectorClock initTime = classInitTime.get(event.getRRClass());
        //     initTime.copy(tV);
        // }
        // incEpochAndCV(st, null);
        super.classInitialized(event);
        // if (COUNT_OPERATIONS)
            // other.inc(st.getTid());
    }

    @Override
    public void classAccessed(ClassAccessedEvent event) {
        // final ShadowThread st = event.getThread();
        // synchronized (classInitTime) {
        //     final VectorClock initTime = classInitTime.get(event.getRRClass());
        //     maxEpochAndCV(st, initTime, null);
        // }
        // if (COUNT_OPERATIONS)
        //     other.inc(st.getTid());
    }

    @Override
    public void printXML(XMLWriter xml) {
        for (ShadowThread td : ShadowThread.getThreads()) {
            xml.print("thread", toString(td));
        }
    }

    // protected void error(final AccessEvent ae, final FTVarState x, final String description,
    //         final String prevOp, final int prevTid, final String curOp, final int curTid) {

    //     if (ae instanceof FieldAccessEvent) {
    //         fieldError((FieldAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
    //     } else {
    //         arrayError((ArrayAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
    //     }
    // }

    // protected void arrayError(final ArrayAccessEvent aae, final FTVarState sx,
    //         final String description, final String prevOp, final int prevTid, final String curOp,
    //         final int curTid) {
    //     final ShadowThread st = aae.getThread();
    //     final Object target = aae.getTarget();

    //     if (arrayErrors.stillLooking(aae.getInfo())) {
    //         arrayErrors.error(st, aae.getInfo(), "Alloc Site", ArrayAllocSiteTracker.get(target),
    //                 "Shadow State", sx, "Current Thread", toString(st), "Array",
    //                 Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]", "Message",
    //                 description, "Previous Op", prevOp + " " + ShadowThread.get(prevTid),
    //                 "Currrent Op", curOp + " " + ShadowThread.get(curTid), "Stack",
    //                 ShadowThread.stackDumpForErrorMessage(st));
    //     }
    //     Assert.assertTrue(prevTid != curTid);

    //     aae.getArrayState().specialize();

    //     if (!arrayErrors.stillLooking(aae.getInfo())) {
    //         advance(aae);
    //     }
    // }

    // protected void fieldError(final FieldAccessEvent fae, final FTVarState sx,
    //         final String description, final String prevOp, final int prevTid, final String curOp,
    //         final int curTid) {
    //     final FieldInfo fd = fae.getInfo().getField();
    //     final ShadowThread st = fae.getThread();
    //     final Object target = fae.getTarget();

    //     if (fieldErrors.stillLooking(fd)) {
    //         fieldErrors.error(st, fd, "Shadow State", sx, "Current Thread", toString(st), "Class",
    //                 (target == null ? fd.getOwner() : target.getClass()), "Field",
    //                 Util.objectToIdentityString(target) + "." + fd, "Message", description,
    //                 "Previous Op", prevOp + " " + ShadowThread.get(prevTid), "Currrent Op",
    //                 curOp + " " + ShadowThread.get(curTid), "Stack",
    //                 ShadowThread.stackDumpForErrorMessage(st));
    //     }

    //     Assert.assertTrue(prevTid != curTid);

    //     if (!fieldErrors.stillLooking(fd)) {
    //         advance(fae);
    //     }
    // }
}
