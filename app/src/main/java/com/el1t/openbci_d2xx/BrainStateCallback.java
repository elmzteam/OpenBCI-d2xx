package com.el1t.openbci_d2xx;

/**
 * Created by Lucas on 1/17/15.
 */
public interface BrainStateCallback {
    public void blinkStart();
    public void blinkEnd(double blinkDuration);
    public void alphaStart();
    public void alphaEnd(double alphaDuraction);
}
