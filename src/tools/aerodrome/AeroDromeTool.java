package tools.aerodrome;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import acme.util.Assert;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DefaultValue;
import acme.util.option.CommandLine;
import acme.util.io.XMLWriter;

import rr.RRMain;
import rr.annotations.Abbrev;
import rr.event.AccessEvent;
import rr.event.AccessEvent.Kind;
import rr.meta.SourceLocation;
import rr.meta.MethodInfo;
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

    // private static String locationPairFilename;
    private static boolean fileType;
    private static String transactionFilename;

    // This map contains the race pairs provided in the input file.
    private static ConcurrentHashMap<String, String> transactionLocations;

    // This list contains the method names to exclude.
    private static List<String> methodsToExclude;

    Set<MethodInfo> transactionEndViolations = ConcurrentHashMap.newKeySet();
    Set<SourceLocation> readViolations = ConcurrentHashMap.newKeySet();
    Set<SourceLocation> writeViolations = ConcurrentHashMap.newKeySet();
    Set<SourceLocation> joinViolations = ConcurrentHashMap.newKeySet();
    Set<SourceLocation> acquireViolations = ConcurrentHashMap.newKeySet();


    private static final ThreadLocalCounter readCounter = new ThreadLocalCounter("AD", "Read", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeCounter = new ThreadLocalCounter("AD", "Write", RR.maxTidOption.get());
    private static final ThreadLocalCounter methodBeginCounter = new ThreadLocalCounter("AD", "Method Begin", RR.maxTidOption.get());
    private static final ThreadLocalCounter methodEndCounter = new ThreadLocalCounter("AD", "Method End", RR.maxTidOption.get());
    private static final ThreadLocalCounter transactionBeginCounter = new ThreadLocalCounter("AD", "Transaction Begin", RR.maxTidOption.get());
    private static final ThreadLocalCounter transactionEndCounter = new ThreadLocalCounter("AD", "Transaction End", RR.maxTidOption.get());
    private static final ThreadLocalCounter acquireCounter = new ThreadLocalCounter("AD", "Acquire", RR.maxTidOption.get());
    private static final ThreadLocalCounter releaseCounter = new ThreadLocalCounter("AD", "Release", RR.maxTidOption.get());
    private static final ThreadLocalCounter forkCounter = new ThreadLocalCounter("AD", "Fork", RR.maxTidOption.get());
    private static final ThreadLocalCounter joinCounter = new ThreadLocalCounter("AD", "Join", RR.maxTidOption.get());

    public void checkMethod(MethodEvent me) {
        int tid = me.getThread().getTid();
        if(!methodsToExclude.contains(me.getInfo().toString())){
            if(me.isEnter()) {
                if (methodCallStackHeight.getLocal(tid) == 0){
                    // System.out.println(tid + " Transaction Begin: " + me.getInfo().toString());
                    transactionBegin(me.getThread());
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

    public void checkLocation(AccessEvent me) {
        String sloc = me.getAccessInfo().getLoc().toString();
        if(transactionLocations.containsKey(sloc)) {
            transactionBegin(me.getThread());
        }
        else if (transactionLocations.containsValue(sloc)) {
            transactionEnd2(me.getThread());
        }
    }

    public void readLocationPairFile() {
        try{
            File file = new File(transactionFilename);
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
            File file = new File(transactionFilename);
            FileReader fr = new FileReader(file);
            BufferedReader br = new BufferedReader(fr);
            String line;
            while((line = br.readLine()) != null) {
                methodsToExclude.add(line);
            }
        } catch(IOException e) { e.printStackTrace(); }
    }

    // public void transactionBegin(MethodEvent me){
    public void transactionBegin(ShadowThread st){
        // ShadowThread st = me.getThread();
		int cur_depth = nestingofThreads.get(st);
		nestingofThreads.put(st,  cur_depth + 1);

		if(cur_depth == 0){
			VectorClock C_t = ts_get_clockThread(st);
			VectorClock C_t_begin =ts_get_clockThreadBegin(st);
			C_t_begin.copy(C_t);
		}

        if(COUNT_OPERATIONS) {
            transactionBeginCounter.inc(st.getTid());
        }
    }

    public void transactionEnd(MethodEvent me){
        ShadowThread st = me.getThread();
		nestingofThreads.put(st, nestingofThreads.get(st)-1);
		if(nestingofThreads.get(st) == 0) {
		    if(handshakeAtEndEvent(st)) {
                transactionEndViolations.add(me.getInfo());
                // System.out.println("AERODROME -- transactionEnd -- " + me.getInfo().toString());
            }
            ts_get_clockThread(st).set(st.getTid(), (Integer)(ts_get_clockThread(st).get(st.getTid()) + 1));
		}

        if(COUNT_OPERATIONS) {
            transactionEndCounter.inc(st.getTid());
        }
    }
    public void transactionEnd2(ShadowThread st){
        // ShadowThread st = me.getThread();
		nestingofThreads.put(st, nestingofThreads.get(st)-1);
		if(nestingofThreads.get(st) == 0) {
		    if(handshakeAtEndEvent(st)) {
                // System.out.println("AERODROME -- transactionEnd -- " + me.getInfo().toString());
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


        // locationPairFilename = rr.RRMain.locationPairFileOption.get();
        fileType = rr.RRMain.fileTypeOption.get();
        transactionFilename = rr.RRMain.transactionFileOption.get();

        transactionLocations = new ConcurrentHashMap<String, String>();
        methodsToExclude = new ArrayList<String>();

        if(fileType)
            readMethodExcludeFile();
        else
            readLocationPairFile();
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
            acquireViolations.add(event.getInfo().getLoc());
            // System.out.println("AERODROME -- acquire -- " + event.toString());
        }
        super.acquire(event);

        if (COUNT_OPERATIONS) {
            acquireCounter.inc(st.getTid());
        }
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

        if (COUNT_OPERATIONS) {
            releaseCounter.inc(st.getTid());
        }
    }

    @Override
    public void enter(MethodEvent me) {
        if(fileType)
            checkMethod(me);
        super.enter(me);

        if(COUNT_OPERATIONS) {
            methodBeginCounter.inc(me.getThread().getTid());
        }
    }

    @Override
    public void exit(MethodEvent me) {
        if(fileType)
            checkMethod(me);
        super.exit(me);

        if(COUNT_OPERATIONS) {
            methodEndCounter.inc(me.getThread().getTid());
        }
    }

    @Override
    public void access(final AccessEvent event) {
		ShadowThread st = event.getThread();
        ShadowVar sv = event.getOriginalShadow();
        if (sv instanceof ADVarClocks) {
            ADVarClocks sx = (ADVarClocks) sv;
            if(!fileType) checkLocation(event);
            if (event.isWrite()) {
                write(event, st, sx);
            } else {
                read(event, st, sx);
            }
        } else {
            super.access(event);
        }
    }

    private static final ThreadLocalCounter methodCallStackHeight = new ThreadLocalCounter("AD", "Other (Ignore)", RR.maxTidOption.get());

    protected void read(final AccessEvent event, final ShadowThread st, final ADVarClocks vcs) {

		VectorClock C_t = ts_get_clockThread(st);
		VectorClock W_v = vcs.write;

        if(varToTh.containsKey(vcs) && !varToTh.get(vcs).equals(st) && vcHandling(W_v, W_v, st)) {
            readViolations.add(event.getAccessInfo().getLoc());
            // System.out.println("AERODROME -- read -- " + event.getAccessInfo().getLoc());
        }
		VectorClock R_v = vcs.read;
		R_v.updateWithMax(R_v, C_t);
		VectorClock chR_v = vcs.readcheck;
        chR_v.max2(C_t, st.getTid());
		if(nestingofThreads.get(st) == 0) {
		    ts_get_clockThread(st).set(st.getTid(), (Integer)(ts_get_clockThread(st).get(st.getTid()) + 1));
		}

        if(COUNT_OPERATIONS) {
            readCounter.inc(st.getTid());
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
            writeViolations.add(event.getAccessInfo().getLoc());
            // System.out.println("AERODROME -- write -- " + event.getAccessInfo().getLoc());
        }

        if(COUNT_OPERATIONS) {
            writeCounter.inc(st.getTid());
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

        super.preStart(event);

        if (COUNT_OPERATIONS) {
            forkCounter.inc(st.getTid());
        }
    }

@Override
    public void postJoin(final JoinEvent event) {
        final ShadowThread st = event.getThread();
        final ShadowThread su = event.getJoiningThread();

        if(ShadowThread.getThreads().contains(su)) {
            VectorClock C_u = ts_get_clockThread(su);
            if(vcHandling(C_u, C_u, st)) {
                joinViolations.add(event.getInfo().getLoc());
            }
        }
        super.postJoin(event);

        if (COUNT_OPERATIONS) {
            joinCounter.inc(st.getTid());
        }
    }


    @Override
    public void stop(ShadowThread st) {
        super.stop(st);
    }

    @Override
    public void printXML(XMLWriter xml) {

        for (MethodInfo s : transactionEndViolations) {
            xml.print("violation", "transactionEnd: " + s.toString());
        }
        for (SourceLocation s : readViolations) {
            xml.print("violation", "read: Location -> " + s + " Method -> " + s.getMethod().toString());
        }
        for (SourceLocation s : writeViolations) {
            xml.print("violation", "write:Location ->  " + s + " Method -> " + s.getMethod().toString());
        }
        for (SourceLocation s : acquireViolations) {
            xml.print("violation", "acquire: Location -> " + s + " Method -> " + s.getMethod().toString());
        }
        for (SourceLocation s : joinViolations) {
            xml.print("violation", "join: Location -> " + s + " Method -> " + s.getMethod().toString());
        }
    }
}
