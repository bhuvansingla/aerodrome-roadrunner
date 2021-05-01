import harness.Callback;

class Table {
    boolean forks[];
    int eatctr;

    Table() {
        forks = new boolean[Philo.NUM_PHIL];
        for (int i = 0; i < Philo.NUM_PHIL; ++i)
            forks[i] = true;
    }

    synchronized int getForks(int id) throws InterruptedException {
        int id1 = id;
        int id2 = (id + 1) % Philo.NUM_PHIL;
        System.out.println(id + " check forks[" + id1 + "]=" + forks[id1] + " and forks[" + id2 + "]="+forks[id2]);
        System.out.flush();
        while(! (forks[id1] && forks[id2])) {
            System.out.println(id + " wait for forks");
            wait();
        }
        forks[id1] = forks[id2] = false;
        System.out.println(id + " got forks");
        return eatctr++;
    }

    synchronized void putForks(int id) {
        System.out.println(id + " putforks");
        forks[id] = forks[(id + 1) % Philo.NUM_PHIL] = true;
        notify();
        System.out.println(id + " notify done");
    }
}

class Philo extends Thread {
    private static Callback harness = new Callback();
    static final int NUM_PHIL = 5;
    static final int MAX_EAT = 12;
    int id;
    Table t;

    Philo(int id, Table t) {
        this.id = id;
        this.t = t;
    }

    public void run() {
        System.out.println(id + " run start");
        try {
            int max_eat = 0;
            while (max_eat <  MAX_EAT) {
                System.out.println(id + " let's try to get the forks"); // eat
                max_eat = t.getForks(id);
                System.out.println(id + " have the forks now"); // eat
                long l = (int)(Math.random() * 500) + 20;
                System.gc();
                System.out.println(id + " eating (" + l + ")"); // eat
                sleep(l);
                System.out.println(id + " that was good"); // eat
                t.putForks(id);
            }
        } catch(InterruptedException e) {
            System.out.println(id + " run interrupted");;
        }
    }

    public static void main(String args[]) throws Exception {
		boolean useCallback = false;
		if(args.length >= 1) {
		      for(int i = 0; i<args.length; i++) {
		    	  if (args[i].equals("-c")) {
		    		  useCallback = true;
		              i++;
		              if (i == args.length) {
		                System.out.println("Missing callback class name after \"-c\" flag");
		              }
		              try {
		                Class<Callback> harnessClass = (Class<Callback>)Class.forName(args[i]);
		                harness = harnessClass.newInstance();
		              } catch (Exception e) {
		                throw new RuntimeException("Exception thrown while creating harness class "+args[i],e);
		              }
		              break;
		    	  }
		      }
		}

		if(useCallback)
			harness.start();
        Table tab = new Table();
        Philo[] p = new Philo[NUM_PHIL];
        for (int i=0; i < NUM_PHIL; ++i) {
            p[i] = new Philo(i, tab);
            p[i].start();
        }
        for (int i=0; i < NUM_PHIL; ++i)
            p[i].join();
        // main should terminate last to have the access ctr correctly.
        if(useCallback)
        	harness.stop();
    }
}
