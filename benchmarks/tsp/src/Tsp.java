
/*
 * Copyright (C) 2000 by ETHZ/INF/CS
 * All rights reserved
 *
 * @version $Id$
 * @author Florian Schneider
 */

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Date;
import java.util.StringTokenizer;

import harness.Callback;

public class Tsp {
	public final static boolean debug = false;
	public final static int MAX_TOUR_SIZE = 32;
	public final static int MAX_NUM_TOURS = 5000;
	public final static int BIGINT = 2000000;
	public final static int END_TOUR = -1;
	public final static int ALL_DONE = -1;
	static int nWorkers = 2;
	static int TspSize = MAX_TOUR_SIZE;
	static int StartNode = 0;
	static int NodesFromEnd = 12;
	private static Callback harness = new Callback();

	public static void main(String[] args) {
		boolean useCallback = false;
		int i;
		String fname = "testdata";
		boolean nop = false;

		try {
			fname = args[0];
			int num = 0;
			if (fname.equals("--nop")) {
				nop = true;
				num = 1;
			} else {
				nWorkers = Integer.parseInt(args[1]);
				num = 2;
			}
			if (num < args.length) {
				for (i = num; i < args.length; i++) {
					if (args[i].equals("-c")) {
						useCallback = true;
						i++;
						if (i == args.length) {
							System.out.println("Missing callback class name after \"-c\" flag");
						}
						try {
							Class<Callback> harnessClass = (Class<Callback>) Class.forName(args[i]);
							harness = harnessClass.newInstance();
						} catch (Exception e) {
							throw new RuntimeException("Exception thrown while creating harness class " + args[i], e);
						}
						break;
					}
				}
			}
		} catch (Exception e) {
			System.out.println("usage: java Tsp <input file> <number of threads>\n" + "    or java Tsp --nop");
			System.exit(-1);
		}
		if (!nop) {
			if (useCallback)
				harness.startWarmup();
		}

		// start computation
		System.gc();
		if (!nop) {

			TspSolver.TourStackTop = -1;
			TspSolver.MinTourLen = BIGINT;

			TspSize = read_tsp(fname);
			if (useCallback)
				harness.stopWarmup();

			long start = 0;
			if (!useCallback)
				start = new Date().getTime();
			else
				harness.start();

			/* init arrays */
			for (i = 0; i < MAX_NUM_TOURS; i++) {
				TspSolver.Tours[i] = new TourElement();
				TspSolver.PrioQ[i] = new PrioQElement();
			}

			/* Initialize first tour */
			TspSolver.Tours[0].prefix[0] = StartNode;
			TspSolver.Tours[0].conn = 1;
			TspSolver.Tours[0].last = 0;
			TspSolver.Tours[0].prefix_weight = 0;
			TspSolver.calc_bound(0); /* Sets lower_bound */

			/* Initialize the priority queue structures */
			TspSolver.PrioQ[1].index = 0;
			TspSolver.PrioQ[1].priority = TspSolver.Tours[0].lower_bound;
			TspSolver.PrioQLast = 1;

			/* Put all unused tours in the free tour stack */
			for (i = MAX_NUM_TOURS - 1; i > 0; i--)
				TspSolver.TourStack[++TspSolver.TourStackTop] = i;

			/* create worker threads */
			Thread[] t = new Thread[nWorkers];
			for (i = 0; i < nWorkers; i++) {
				t[i] = new TspSolver();

			}
			for (i = 0; i < nWorkers; i++) {
				t[i].start();
			}

			/* wait for completion */
			try {
				for (i = 0; i < nWorkers; i++) {
					t[i].join();
					// if (debug) System.out.println("joined thread "+i);
				}
			} catch (InterruptedException e) {
			}
			if (useCallback)
				harness.stop();
			else {
				long end = new Date().getTime();

				System.out.println("tsp-" + nWorkers + "\t" + ((int) end - (int) start));
			}
			System.out.println("Minimum tour length: " + TspSolver.MinTourLen);
			System.out.print("Minimum tour:");
			TspSolver.MakeTourString(TspSize, TspSolver.MinTour);
		}
	}

	static int read_tsp(String fname) {
		String line;
		StringTokenizer tok;
		int i, j;

		try {
			BufferedReader in = new BufferedReader(new FileReader(fname));
			TspSize = Integer.parseInt(in.readLine());

			for (i = 0; i < TspSize; i++) {
				line = in.readLine();
				tok = new StringTokenizer(line, " ");
				j = 0;
				while (tok.hasMoreElements())
					TspSolver.weights[i][j++] = Integer.parseInt((String) tok.nextElement());
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
		return (TspSize);
	}
}
