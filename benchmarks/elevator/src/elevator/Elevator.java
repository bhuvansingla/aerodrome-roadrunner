package elevator;
/*
 * Copyright (C) 2000 by ETHZ/INF/CS
 * All rights reserved
 *
 * @version $Id$
 * @author Roger Karrer
 */

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StreamTokenizer;
import java.util.Date;
import java.util.Vector;

import harness.Callback;

public class Elevator {

    // shared control object
    private Controls controls;
    private Vector events;
    private Lift[] lifts;
    private int numberOfLifts;
    private static Callback harness = new Callback();

    // Initializer for main class, reads the input and initlizes
    // the events Vector with ButtonPress objects
    private Elevator(String file) {
        InputStreamReader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file));
        } catch (FileNotFoundException e) {
            e.printStackTrace(); // To change body of catch statement use File | Settings | File Templates.
            System.exit(1);
        }
        StreamTokenizer st = new StreamTokenizer(reader);
        st.lowerCaseMode(true);
        st.parseNumbers();

        events = new Vector();

        int numFloors = 0, numLifts = 0;
        try {
            numFloors = readNum(st);
            numLifts = readNum(st);

            int time = 0, to = 0, from = 0;
            do {
                time = readNum(st);
                if (time != 0) {
                    from = readNum(st);
                    to = readNum(st);
                    events.addElement(new ButtonPress(time, from, to));
                }
            } while (time != 0);
        } catch (IOException e) {
            System.err.println("error reading input: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }

        // Create the shared control object
        controls = new Controls(numFloors);
        numberOfLifts = numLifts;
        lifts = new Lift[numLifts];
        // Create the elevators
        for (int i = 0; i < numLifts; i++)
            lifts[i] = new Lift(numFloors, controls);
    }

    // Press the buttons at the correct time
    private void begin() {
        // Get the thread that this method is executing in
        Thread me = Thread.currentThread();
        // First tick is 1
        int time = 1;

        for (int i = 0; i < events.size();) {
            ButtonPress bp = (ButtonPress) events.elementAt(i);
            // if the current tick matches the time of th next event
            // push the correct buttton
            if (time == bp.time) {
                System.out.println("Elevator::begin - its time to press a button");
                if (bp.onFloor > bp.toFloor)
                    controls.pushDown(bp.onFloor, bp.toFloor);
                else
                    controls.pushUp(bp.onFloor, bp.toFloor);
                i += 1;
            }
            // wait 1/2 second to next tick
            try {
                me.sleep(500);
            } catch (InterruptedException e) {
            }
            time += 1;
        }
    }

    private int readNum(StreamTokenizer st) throws IOException {
        int tokenType = st.nextToken();

        if (tokenType != StreamTokenizer.TT_NUMBER)
            throw new IOException("Number expected!");
        return (int) st.nval;
    }

    private void waitForLiftsToFinishOperation() {
        for (int i = 0; i < numberOfLifts; i++) {
            try {
                lifts[i].join();
            } catch (InterruptedException e) {
                System.err.println("Error while waitinf for lift" + i + "to finish");
            }
        }
    }

    public static void main(String args[]) {
        boolean useCallback = false;
        if (args.length < 1) {
            System.out.println("Usage: java elevator.Elevator input");
        } else {
            if (args.length > 1) {
                for (int i = 1; i < args.length; i++) {
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
        }
        Elevator building = new Elevator(args[0]);
        long start = 0;
        if (!useCallback)
            start = new Date().getTime();
        else
            harness.start();
        building.begin();
        building.waitForLiftsToFinishOperation();
        if (useCallback)
            harness.stop();
        else {
            long end = new Date().getTime();
            System.out.println("Time taken in ms : " + (end - start));
        }
    }
}
