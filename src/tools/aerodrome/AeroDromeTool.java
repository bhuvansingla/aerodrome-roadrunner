package tools.aerodrome;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import acme.util.Assert;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DefaultValue;
import acme.util.option.CommandLine;
import rr.RRMain;
import rr.annotations.Abbrev;
import rr.event.AccessEvent;
import rr.event.AccessEvent.Kind;
import rr.event.AcquireEvent;
import rr.event.MethodEvent;
import rr.event.StartEvent;
import rr.event.JoinEvent;
import rr.event.NewThreadEvent;
import rr.event.ReleaseEvent;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.tool.RR;
import rr.tool.Tool;
import tools.util.VectorClock;

@Abbrev("AD")
public class AeroDromeTool extends Tool {

    private static final boolean COUNT_OPERATIONS = RRMain.slowMode();
    private static final int INIT_VECTOR_CLOCK_SIZE = 16;

    public ConcurrentHashMap <ADVarClocks, Integer> varsTrack;
    private int nVars;

    public ConcurrentHashMap <ShadowLock, ShadowThread> lockToTh;
    public ConcurrentHashMap <ShadowVar, ShadowThread> varToTh;
    public ConcurrentHashMap <ShadowThread, Integer> nestingofThreads;

    private static String locationPairFilename;
    private static String methodExcludeFilename;

    // This map contains the race pairs provided in the input file.
    private static ConcurrentHashMap<String, String> transactionLocations;

    // This list contains the method names to exclude.
    private static List<String> methodsToExclude;

    public void checkMethod(MethodEvent me) {
        int tid = me.getThread().getTid();
        if(!methodsToExclude.contains(me.getInfo().toString())){
            if(me.isEnter()) {
                if (methodCallStackHeight.getLocal(tid) == 0){
                    // System.out.println(tid + " Transaction Begin: " + me.getInfo().toString());
                    transactionBegin(me);
                }
                else {
                    // System.out.println(tid + " (Redundant) Transaction Begin: " + me.getInfo().toString());
                }
                methodCallStackHeight.inc(tid);
            }
            else {
                methodCallStackHeight.dec(tid);
                if(methodCallStackHeight.getLocal(tid) == 0) {
                    // System.out.println(tid + " Transaction End: " + me.getInfo().toString());
                    transactionEnd(me);
                }
                else{
                    // System.out.println(tid + " (Redundant) Transaction End: " + me.getInfo().toString());
                }
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

    public void transactionBegin(MethodEvent me){
        ShadowThread st = me.getThread();
		int cur_depth = nestingofThreads.get(st);
		nestingofThreads.put(st,  cur_depth + 1);

		if(cur_depth == 0){
			VectorClock C_t = ts_get_clockThread(st);
			VectorClock C_t_begin =ts_get_clockThreadBegin(st);
			C_t_begin.copy(C_t);
		}
    }

    public void transactionEnd(MethodEvent me){
        ShadowThread st = me.getThread();
		nestingofThreads.put(st, nestingofThreads.get(st)-1);
		if(nestingofThreads.get(st) == 0) {
		    if(handshakeAtEndEvent(st)) {
                System.out.println("AERODROME -- transactionEnd -- " + me.getInfo().toString());
            }
            ts_get_clockThread(st).set(st.getTid(), (Integer)(ts_get_clockThread(st).get(st.getTid()) + 1));
		}
    }

    public boolean handshakeAtEndEvent(ShadowThread st) {
		int tid = st.getTid();
        VectorClock C_t_begin = ts_get_clockThreadBegin(st);;
		VectorClock C_t = ts_get_clockThread(st);
		for(ShadowThread u: ShadowThread.getThreads()) {
			if(!u.equals(st)) {
				VectorClock C_u = ts_get_clockThread(u);
				if(C_t_begin.isLessThanOrEqual(C_u, tid) && vcHandling(C_t, C_t, u)) {
                    return true;
                }
			}
		}
		for(ShadowLock l: st.getLocksHeld()) {
			VectorClock L_l = clockLock.get(l);
			if(C_t_begin.isLessThanOrEqual(L_l, tid)) {
				L_l.updateWithMax(L_l, C_t);
			}
		}
		for(ADVarClocks v: varsTrack.keySet()) {
			VectorClock W_v = v.write;
			if(C_t_begin.isLessThanOrEqual(W_v, tid)) {
				W_v.updateWithMax(W_v, C_t);
			}
			VectorClock R_v = v.read;
			VectorClock chR_v = v.readcheck;
			if(C_t_begin.isLessThanOrEqual(R_v, tid)) {
				R_v.updateWithMax(R_v, C_t);
                chR_v.max2(C_t, tid);
			}
		}
		return false;
	}


    public AeroDromeTool(final String name, final Tool next, CommandLine commandLine) {
        super(name, next, commandLine);
        nVars = 0;
        varsTrack = new ConcurrentHashMap<ADVarClocks, Integer>();

        lockToTh = new ConcurrentHashMap<ShadowLock, ShadowThread>();
        varToTh = new ConcurrentHashMap<ShadowVar, ShadowThread>();
        nestingofThreads = new ConcurrentHashMap<ShadowThread, Integer>();


        locationPairFilename = rr.RRMain.locationPairFileOption.get();
        methodExcludeFilename = rr.RRMain.methodExcludeFileOption.get();

        transactionLocations = new ConcurrentHashMap<String, String>();
        methodsToExclude = new ArrayList<String>();


        readLocationPairFile();
        readMethodExcludeFile();
    }

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

    public static final Decoration<ShadowLock, VectorClock> clockLock = ShadowLock.makeDecoration("AE:clockLock",
    DecorationFactory.Type.MULTIPLE, new DefaultValue<ShadowLock, VectorClock>() {
        public VectorClock get(ShadowLock ld) {
            return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
        }
    });

    @Override
    public ShadowVar makeShadowVar(final AccessEvent event) {
        if (event.getKind() == Kind.VOLATILE) {
            return super.makeShadowVar(event);
        } else {

            ADVarClocks vcs = new ADVarClocks(INIT_VECTOR_CLOCK_SIZE);

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

        nestingofThreads.put(st, 0);

        if (ts_get_clockThread(st) == null) {
            final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
            ts_set_clockThread(st, tV);
        }

        ts_get_clockThread(st).set(st.getTid(), 1);

        if (ts_get_clockThreadBegin(st) == null) {
            final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
            ts_set_clockThreadBegin(st, tV);
        }

        super.create(event);
    }

    @Override
    public void acquire(final AcquireEvent event) {
        final ShadowThread st = event.getThread();
        ShadowLock sl = event.getLock();
        if(!st.getLocksHeld().contains(sl)) {
			clockLock.set(sl, new VectorClock(INIT_VECTOR_CLOCK_SIZE));
        }
		VectorClock L_l = clockLock.get(sl);

		if(lockToTh.containsKey(sl) && !lockToTh.get(sl).equals(st) && vcHandling(L_l, L_l, st)) {
            System.out.println("AERODROME -- acquire -- " + event.toString());
        }
        super.acquire(event);
        // if (COUNT_OPERATIONS)
        //     acquire.inc(st.getTid());
    }

    public boolean vcHandling(VectorClock checkClock, VectorClock fromClock, ShadowThread target) {
        boolean violationDetected = false;
		VectorClock C_target_begin = ts_get_clockThreadBegin(target);
		if(C_target_begin.isLessThanOrEqual(checkClock, target.getTid()) && nestingofThreads.get(target) > 0) {
			violationDetected = true;
		}
		VectorClock C_target = ts_get_clockThread(target);
		C_target.updateWithMax(C_target, fromClock);
		return violationDetected;
	}

    @Override
    public void release(final ReleaseEvent event) {
        final ShadowThread st = event.getThread();
        ShadowLock sl = event.getLock();
		if(!st.getLocksHeld().contains(sl)) {
			clockLock.set(sl, new VectorClock(INIT_VECTOR_CLOCK_SIZE));
        }
		VectorClock C_t = ts_get_clockThread(st);
		VectorClock L_l = clockLock.get(sl);

		L_l.copy(C_t);
		lockToTh.put(sl, st);
		if(nestingofThreads.get(st) == 0) {
		    ts_get_clockThread(st).set(st.getTid(), (Integer)(ts_get_clockThread(st).get(st.getTid()) + 1));
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
		ShadowThread st = event.getThread();
        ShadowVar sv = event.getOriginalShadow();
        if (sv instanceof ADVarClocks) {
            ADVarClocks sx = (ADVarClocks) sv;
            if (event.isWrite()) {
                write(event, st, sx);
            } else {
                read(event, st, sx);
            }
        } else {
            super.access(event);
        }
    }

    private static final ThreadLocalCounter methodCallStackHeight = new ThreadLocalCounter("AD", "MethodCallStackHeight", RR.maxTidOption.get());

    protected void read(final AccessEvent event, final ShadowThread st, final ADVarClocks vcs) {

		VectorClock C_t = ts_get_clockThread(st);
		VectorClock W_v = vcs.write;

        if(varToTh.containsKey(vcs) && !varToTh.get(vcs).equals(st) && vcHandling(W_v, W_v, st)) {
            System.out.println("AERODROME -- read -- " + event.getAccessInfo().getLoc());
        }
		VectorClock R_v = vcs.read;
		R_v.updateWithMax(R_v, C_t);
		VectorClock chR_v = vcs.readcheck;
        chR_v.max2(C_t, st.getTid());
		if(nestingofThreads.get(st) == 0) {
		    ts_get_clockThread(st).set(st.getTid(), (Integer)(ts_get_clockThread(st).get(st.getTid()) + 1));
		}
    }


    protected void write(final AccessEvent event, final ShadowThread st, final ADVarClocks vcs) {
        boolean violationDetected = false;
		VectorClock W_v = vcs.write;
		VectorClock R_v = vcs.read;
		VectorClock chR_v = vcs.readcheck;
		VectorClock C_t = ts_get_clockThread(st);

		if(varToTh.containsKey(vcs)) {
			if(!varToTh.get(vcs).equals(st)) {
				violationDetected |= vcHandling(W_v, W_v, st);
			}
		}
		violationDetected |= vcHandling(chR_v, R_v, st);
		W_v.copy(C_t);
		varToTh.put(vcs, st);
		if(nestingofThreads.get(st) == 0) {
		    ts_get_clockThread(st).set(st.getTid(), (Integer)(ts_get_clockThread(st).get(st.getTid()) + 1));
		}
        if(violationDetected) {
            System.out.println("AERODROME -- write -- " + event.getAccessInfo().getLoc());
        }
    }

    @Override
    public void preStart(final StartEvent event) {
        final ShadowThread st = event.getThread();
        final ShadowThread su = event.getNewThread();

        if(ShadowThread.getThreads().contains(su)) {
            VectorClock C_u = ts_get_clockThread(su);
            VectorClock C_t = ts_get_clockThread(st);
            C_u.updateWithMax(C_u, C_t);
            if(nestingofThreads.get(st) == 0) {
                ts_get_clockThread(st).set(st.getTid(), (Integer)(ts_get_clockThread(st).get(st.getTid())+1));
            }
        }
        /*
         * Safe to access su.V, because u has not started yet. This will give us exclusive access to
         * it. There may be a race if two or more threads race are starting u, but of course, a
         * second attempt to start u will crash... RR guarantees that the forked thread will
         * synchronize with thread t before it does anything else.
         */
        // maxAndIncEpochAndCV(su, tV, event.getInfo());
        // incEpochAndCV(st, event.getInfo());

        super.preStart(event);
        // if (COUNT_OPERATIONS)
        //     fork.inc(st.getTid());
    }

@Override
    public void postJoin(final JoinEvent event) {
        final ShadowThread st = event.getThread();
        final ShadowThread su = event.getJoiningThread();

        if(ShadowThread.getThreads().contains(su)) {
            VectorClock C_u = ts_get_clockThread(su);
            if(vcHandling(C_u, C_u, st)) {
                // Return true - a problem here?
            }
        }
        super.postJoin(event);
        // if (COUNT_OPERATIONS)
        //     join.inc(st.getTid());
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
}
