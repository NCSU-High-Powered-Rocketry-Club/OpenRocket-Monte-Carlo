package com.hprc.montecarlo; 

import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.simulation.extension.AbstractSimulationExtensionProvider;

@Plugin 
public class MCExtensionProvider extends AbstractSimulationExtensionProvider {

    public MCExtensionProvider() {
        super(MonteCarloExtension.class, "NCSU HPRC", "Monte Carlo Wrapper Dev");
    }
}