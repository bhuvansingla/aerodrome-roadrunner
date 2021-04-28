/******************************************************************************
 *
 * Copyright (c) 2010, Cormac Flanagan (University of California, Santa Cruz) and Stephen Freund
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
/*
  Trying to recreate the following race:
  Cycle 1: T2's aqr-rel to T3's aqr-rel back to T2's aqr-rel
  Cycle 2: T2 W(Y) to T1 R(y) to T1 R(X) back to T2 W(x)
  Cycle 3: T3 W(Z) to T1 R(Z) to T1 R(X) back to T3 R(X)
  
  T1            T2            T3
  begin         begin         Begin
  ~             Aqr(m)        ~
  ~             Rel(m)        ~
  ~             ~             Aqr(m)
  ~             W(y)          ~
  R(y)          ~             Rel(m)
  ~             ~             ~
  ~             Aqr(m)        ~
  ~             Rel(m)        ~
  ~             ~             W(z)
  ~             ~             ~
  R(Z)          ~             ~
  R(x)          ~             ~
  ~             W(x)          ~
  ~             ~             R(x)


*/
package test;

public class YTest6 extends Thread {

    static final int ITERS = 1;

    static int y;
    static int z;
    static int x;
    static final Object m = new Object();
    @Override
    public void run() {
    }

    public static class Test1 extends Thread implements Runnable {
      int temp;
      public void run() {
        for (int i = 0; i < ITERS; i++) {
          try{Thread.sleep(300);}catch(InterruptedException e){System.out.println(e);}
          temp = y;
          System.out.println(i + ". "+Thread.currentThread().getName() + " R(Y)" +" now sleeping" );
          try{Thread.sleep(300);}catch(InterruptedException e){System.out.println(e);}
          temp = z;
          System.out.println(i + ". "+Thread.currentThread().getName() + " R(Z)" );
          temp = x;
          System.out.println(i + ". "+Thread.currentThread().getName() + " R(X)" );
        }
    }
  }
  public static class Test2 extends Thread implements Runnable {
      int temp;
      public void run() {
        for (int i = 0; i < ITERS; i++) {
          synchronized (m) {
                System.out.println(i + ". "+Thread.currentThread().getName() + " Aqr(m)");
		      }
          System.out.println(i + ". "+Thread.currentThread().getName() + " Rel(m)");
          try{Thread.sleep(150);}catch(InterruptedException e){System.out.println(e);}
          y = 2;
          System.out.println(i + ". "+Thread.currentThread().getName() + " W(Y)" );
          try{Thread.sleep(200);}catch(InterruptedException e){System.out.println(e);}
          synchronized (m) {
                System.out.println(i + ". "+Thread.currentThread().getName() + " Aqr(m)");
		      }
          System.out.println(i + ". "+Thread.currentThread().getName() + " Rel(m)" +" now sleeping" );
          try{Thread.sleep(400);}catch(InterruptedException e){System.out.println(e);}
          x=2;
          System.out.println(i + ". "+Thread.currentThread().getName() + " W(X)" );
      }
      }
}
public static class Test3 extends Thread implements Runnable {
    int temp;
    public void run() {
      for (int i = 0; i < ITERS; i++) {
        try{Thread.sleep(100);}catch(InterruptedException e){System.out.println(e);}
        synchronized (m) {
              System.out.println(i + ". "+Thread.currentThread().getName() + " Aqr(m)");
              try{Thread.sleep(100);}catch(InterruptedException e){System.out.println(e);}
        }
        System.out.println(i + ". "+Thread.currentThread().getName() + " Rel(m)");
        try{Thread.sleep(200);}catch(InterruptedException e){System.out.println(e);}
        z = 3;
        System.out.println(i + ". "+Thread.currentThread().getName() + " W(Z)" );
        try{Thread.sleep(200);}catch(InterruptedException e){System.out.println(e);}
        temp = x;
        System.out.println(i + ". "+Thread.currentThread().getName() + " R(X)" );
    }
    }
}
    public static void main(String args[]) throws Exception {
        final Test1 t1 = new Test1();
        final Test2 t2 = new Test2();
        final Test3 t3 = new Test3();
        t1.start();
        t2.start();
        t3.start();
        t1.join();
        t2.join();
        t3.join();
    }
}
