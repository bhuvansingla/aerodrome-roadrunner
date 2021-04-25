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

@Abbrev("AD")
public class AerodromeTool extends Tool implements BarrierListener<FTBarrierState> {

    private static final boolean COUNT_OPERATIONS = RRMain.slowMode();
    private static final int INIT_VECTOR_CLOCK_SIZE = 16;

    public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages
            .makeFieldErrorMessage("FastTrack");
    public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages
            .makeArrayErrorMessage("FastTrack");

    private final AEVectorClock maxEpochPerTid = new AEVectorClock(INIT_VECTOR_CLOCK_SIZE);

    // public ConcurrentHashMap <ShadowThread, Integer> threadToIndex;
    // public ConcurrentHashMap <ShadowLock, Integer> lockToIndex;
    public ConcurrentHashMap <AEVarClocks, Integer> varsTrack;
    // private int nTh;
    // private int nLock;
    private int nVars;

    public ConcurrentHashMap <ShadowLock, ShadowThread> lockToTh;
    public ConcurrentHashMap <ShadowVar, ShadowThread> varToTh;
    public ConcurrentHashMap <ShadowThread, Integer> nestingofThreads;

    // public static class TransactionHandling {
    private static String locationPairFilename;
    private static String methodExcludeFilename;
    // This map contains the race pairs provided in the input file.
    private static ConcurrentHashMap<String, String> transactionLocations;

    // This list contains the method names to exclude.
    private static List<String> methodsToExclude;

    // public TransactionHandling() {
        
    // }


    public void checkMethod(MethodEvent me) {
        if(!methodsToExclude.contains(me.getInfo().toString())){
            if(me.isEnter()) {
                // System.out.println("Entering: " + me.getInfo().toString());
                transactionBegin(me);
            }
            else {
                // System.out.println("Exiting: " + me.getInfo().toString());
                transactionEnd(me);
            }
        }
        else {
            // System.out.println("Excluding: " + me.getInfo().toString());
        }
    }

    // public void CheckLocation(String sloc) {
    //     if(transactionLocations.containsKey(sloc)) {
    //         transactionBegin();
    //     } else if (transactionLocations.containsValue(sloc)) {
    //         transactionEnd();
    //     }
    // }
    public void readLocationPairFile() {
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

    public void readMethodExcludeFile() {
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
    // }

    public void transactionBegin(MethodEvent me){
        ShadowThread st = me.getThread();
		int cur_depth = nestingofThreads.get(st);
		nestingofThreads.put(st,  cur_depth + 1);
		boolean violationDetected = false;

		if(cur_depth == 0){
			AEVectorClock C_t = ts_get_clockThread(st);				
			AEVectorClock C_t_begin =ts_get_clockThreadBegin(st);
			C_t_begin.copyFrom(C_t);
		}
    }

    public void transactionEnd(MethodEvent me){
        ShadowThread st = me.getThread();
		nestingofThreads.put(st, nestingofThreads.get(st)-1);
		if(nestingofThreads.get(st) == 0) {
		    if(handshakeAtEndEvent(st)) {
                System.out.println("AERODROME -- transactionEnd -- " + me.getInfo().toString());
            }
            ts_get_clockThread(st).setClockIndex(st.getTid(), (Integer)(ts_get_clockThread(st).getClockIndex(st.getTid()) + 1));	
		}
    }

    public boolean handshakeAtEndEvent(ShadowThread st) {
		boolean violationDetected = false;
		int tid = st.getTid();
        AEVectorClock C_t_begin = ts_get_clockThreadBegin(st);;
		AEVectorClock C_t = ts_get_clockThread(st);
		for(ShadowThread u: st.getThreads()) {
			if(!u.equals(st)) {
				AEVectorClock C_u = ts_get_clockThread(u);
				if(C_t_begin.isLessThanOrEqual(C_u, tid) && vcHandling(C_t, C_t, u)) {
                    return true;
                }
			}
		}
		for(ShadowLock l: st.getLocksHeld()) {
			AEVectorClock L_l = clockLock.get(l);
			if(C_t_begin.isLessThanOrEqual(L_l, tid)) {
				L_l.updateWithMax(L_l, C_t);
			}
		}
		for(AEVarClocks v: varsTrack.keySet()) {
			AEVectorClock W_v = v.write;
			if(C_t_begin.isLessThanOrEqual(W_v, tid)) {
				W_v.updateWithMax(W_v, C_t);
			}
			AEVectorClock R_v = v.read;
			AEVectorClock chR_v = v.readcheck;
			if(C_t_begin.isLessThanOrEqual(R_v, tid)) {
				R_v.updateWithMax(R_v, C_t);

                chR_v.updateMax2WithoutLocal(C_t, tid);
			}
		}
		return false;
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
    // TransactionHandling th;`
    public AerodromeTool(final String name, final Tool next, CommandLine commandLine) {
        super(name, next, commandLine);
        // th = new TransactionHandling();
        // nTh = 0;
        // threadToIndex = new ConcurrentHashMap<ShadowThread, Integer>();
        // nLock = 0;
        // lockToIndex = new ConcurrentHashMap<ShadowLock, Integer>();
        nVars = 0;
        varsTrack = new ConcurrentHashMap<AEVarClocks, Integer>();

        lockToTh = new ConcurrentHashMap<ShadowLock, ShadowThread>();
        varToTh = new ConcurrentHashMap<ShadowVar, ShadowThread>();
        nestingofThreads = new ConcurrentHashMap<ShadowThread, Integer>();


        locationPairFilename = rr.RRMain.locationPairFileOption.get();
        methodExcludeFilename = rr.RRMain.methodExcludeFileOption.get();

        transactionLocations = new ConcurrentHashMap<String, String>();
        methodsToExclude = new ArrayList<String>();


        readLocationPairFile();
        readMethodExcludeFile();

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

            if(!varsTrack.containsKey(vcs)){
                varsTrack.put(vcs, nVars);
                nVars ++;
            }

            return vcs;
        }
    }

    @Override
    public void create(NewThreadEvent event) {
        
        final ShadowThread st = event.getThread();
        // threadToIndex.put(st, (Integer)nTh);
        // nTh++;

        nestingofThreads.put(st, 0);

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
        if(!st.getLocksHeld().contains(sl)) {
			clockLock.set(sl, new AEVectorClock(INIT_VECTOR_CLOCK_SIZE));
        }
		AEVectorClock L_l = clockLock.get(sl);

		if(lockToTh.containsKey(sl) && !lockToTh.get(sl).equals(st) && vcHandling(L_l, L_l, st)) {
            System.out.println("AERODROME -- acquire -- " + event.toString());
        }
        // if(lockToTh.containsKey(sl)) {
		// 	if(!lockToTh.get(sl).equals(st)) {
		// 		if(vcHandling(L_l, L_l, st)) {
        //             System.out.println("AERODROME -- acquire -- " + event.toString());
        //         }
		// 	}
		// }
        super.acquire(event);
		// return violationDetected; 
        // TODO : ERROR DETECTED 
        // if (COUNT_OPERATIONS)
        //     acquire.inc(st.getTid());
    }

    public boolean vcHandling(AEVectorClock checkClock, AEVectorClock fromClock, ShadowThread target) {
		// int tIndex = threadToIndex.get(target);
		// int tid = target.getTid();
        boolean violationDetected = false;
		AEVectorClock C_target_begin = ts_get_clockThreadBegin(target);
		if(C_target_begin.isLessThanOrEqual(checkClock, target.getTid()) && nestingofThreads.get(target) > 0) {
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
		if(!st.getLocksHeld().contains(sl)) {
			clockLock.set(sl, new AEVectorClock(INIT_VECTOR_CLOCK_SIZE));
        }
		AEVectorClock C_t = ts_get_clockThread(st);
		AEVectorClock L_l = clockLock.get(sl);

		L_l.copyFrom(C_t);
		lockToTh.put(sl, st);
		if(nestingofThreads.get(st) == 0) {
		    ts_get_clockThread(st).setClockIndex(st.getTid(), (Integer)(ts_get_clockThread(st).getClockIndex(st.getTid()) + 1));
			// incClockThread(st);
		}
        super.release(event);
        // if (COUNT_OPERATIONS)
        //     release.inc(st.getTid());
    }

    @Override
    public void enter(MethodEvent me) {
        checkMethod(me);
        super.enter(me);
    }
    
    @Override
    public void exit(MethodEvent me) {
        checkMethod(me);
        super.exit(me);
    }

    @Override
    public void access(final AccessEvent event) {
        SourceLocation sl = event.getAccessInfo().getLoc();
		ShadowThread st = event.getThread();
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

    protected void read(final AccessEvent event, final ShadowThread st, final AEVarClocks vcs) {

		AEVectorClock C_t = ts_get_clockThread(st);
		AEVectorClock W_v = vcs.write;

        if(varToTh.containsKey(vcs) && !varToTh.get(vcs).equals(st) && vcHandling(W_v, W_v, st)) {
            System.out.println("AERODROME -- read -- " + event.getAccessInfo().getLoc());
        }
		// if(varToTh.containsKey(vcs)) {
		// 	if(!varToTh.get(vcs).equals(st)) {
        //         if(vcHandling(W_v, W_v, st)) {
        //             System.out.println("AERODROME -- read -- " + event.getAccessInfo().getLoc());
        //         }
		// 	}
		// }
		AEVectorClock R_v = vcs.read;
		R_v.updateWithMax(R_v, C_t);
		AEVectorClock chR_v = vcs.readcheck;
        // chR_v.updateMax2WithoutLocal(C_t, st.getTid()threadToIndex.get(st));
        chR_v.updateMax2WithoutLocal(C_t, st.getTid());
		if(nestingofThreads.get(st) == 0) {
		    ts_get_clockThread(st).setClockIndex(st.getTid(), (Integer)(ts_get_clockThread(st).getClockIndex(st.getTid()) + 1));
		}
		// return violationDetected; // TODO : Handle Error
    }


    protected void write(final AccessEvent event, final ShadowThread st, final AEVarClocks vcs) {
        boolean violationDetected = false;
		AEVectorClock W_v = vcs.write;
		AEVectorClock R_v = vcs.read;
		AEVectorClock chR_v = vcs.readcheck;
		AEVectorClock C_t = ts_get_clockThread(st);

		if(varToTh.containsKey(vcs)) {
			if(!varToTh.get(vcs).equals(st)) {
				violationDetected |= vcHandling(W_v, W_v, st);
			}
		}
		violationDetected |= vcHandling(chR_v, R_v, st);
		W_v.copyFrom(C_t);
		varToTh.put(vcs, st);
		if(nestingofThreads.get(st) == 0) {
		    ts_get_clockThread(st).setClockIndex(st.getTid(), (Integer)(ts_get_clockThread(st).getClockIndex(st.getTid()) + 1));
            // incClockThread(st);
		}
        if(violationDetected) {  
            System.out.println("AERODROME -- write -- " + event.getAccessInfo().getLoc());
        }
    }

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
    public void classAccessed(ClassAccessedEvent event) {}

    @Override
    public void printXML(XMLWriter xml) {
        for (ShadowThread td : ShadowThread.getThreads()) {
            xml.print("thread", toString(td));
        }
    }

}
