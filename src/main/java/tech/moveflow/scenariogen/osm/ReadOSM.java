package tech.moveflow.scenariogen.osm;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.contrib.osm.networkReader.SupersonicOsmNetworkReader;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.StrategyConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;

import java.io.IOException;
import java.util.*;

public class ReadOSM {

    private static final String BASE_PATH = "input/";

    private static final double SAMPLE = 1.;
    private static final double TOTAL_POPULATION = 10000;

    public static void main(String... args) throws IOException {
//        OsmNetworkReader

        Config c = ConfigUtils.createConfig();

        c.plans().setInputFile("population.xml");
        c.network().setInputFile("network.xml");

        c.controler().setOutputDirectory(BASE_PATH + "/output");
        c.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        c.global().setNumberOfThreads(8);

        c.controler().setLastIteration(100);

        Scenario sc = ScenarioUtils.createScenario(c);
        CoordinateTransformation trans = new GeotoolsTransformation("EPSG:4326", "EPSG:32617");

        //free speed for pedestrians ~1.3 m/s, laneCapacity is the maximum number of pedestrians per lane and hour
        LinkProperties props = new LinkProperties(1, 2, 1.3, 4680, false);
        SupersonicOsmNetworkReader osm = SupersonicOsmNetworkReader.builder().coordinateTransformation(trans)
                .addOverridingLinkProperties("footway", props)
                .build();
        Network net = osm.read(BASE_PATH + "map.pbf");
        new NetworkWriter(net).write(BASE_PATH + "network.xml");
        new MatsimNetworkReader(sc.getNetwork()).readFile(BASE_PATH + "network.xml");

        NetworkCleaner nw = new NetworkCleaner();
        nw.run(sc.getNetwork());

        sc.getConfig().qsim().setFlowCapFactor(SAMPLE);
        sc.getConfig().qsim().setStorageCapFactor(SAMPLE);
        new NetworkWriter(sc.getNetwork()).write(BASE_PATH + "network.xml");


        genPopulation((int) (TOTAL_POPULATION / SAMPLE + 0.5), sc);
        new PopulationWriter(sc.getPopulation()).write(BASE_PATH + "population.xml");


        Collection<PlanCalcScoreConfigGroup.ActivityParams> params = c.planCalcScore().getActivityParams();

        PlanCalcScoreConfigGroup.ActivityParams home = new PlanCalcScoreConfigGroup.ActivityParams();
        home.setActivityType("h");
        home.setMinimalDuration(8 * 3600);
        home.setEarliestEndTime(6 * 3600);
        home.setTypicalDuration(16 * 3600);
        c.planCalcScore().addActivityParams(home);
        PlanCalcScoreConfigGroup.ActivityParams work = new PlanCalcScoreConfigGroup.ActivityParams();
        work.setActivityType("w");
        work.setMinimalDuration(6 * 3600);
        work.setEarliestEndTime(15 * 3600);
        work.setTypicalDuration(8 * 3600);
        work.setOpeningTime(7 * 3600);
        work.setClosingTime(19 * 3600);


        c.planCalcScore().addActivityParams(work);

        c.strategy().setFractionOfIterationsToDisableInnovation(0.8);

        StrategyConfigGroup.StrategySettings expBeta = new StrategyConfigGroup.StrategySettings();
        expBeta.setStrategyName("ChangeExpBeta");
        expBeta.setWeight(0.8);
        c.strategy().addStrategySettings(expBeta);

        StrategyConfigGroup.StrategySettings rr = new StrategyConfigGroup.StrategySettings();
        rr.setStrategyName("ReRoute");
        rr.setWeight(0.1);
        c.strategy().addStrategySettings(rr);

        StrategyConfigGroup.StrategySettings tt = new StrategyConfigGroup.StrategySettings();
        tt.setStrategyName("TimeAllocationMutator");
        tt.setWeight(0.1);
        c.strategy().addStrategySettings(tt);


        new ConfigWriter(c).write(BASE_PATH + "config.xml");

        Controler ctr = new Controler(BASE_PATH + "config.xml");
        ctr.run();

    }


    private static void genPopulation(int total, Scenario sc) {

        Random r = new Random(42);

        Network net = sc.getNetwork();
        double totalLength = 0;
        List<Link> links = new ArrayList<>(net.getLinks().values());
        TreeMap<Double, Link> tm = new TreeMap<Double, Link>();
        for (Link l : links) {
            tm.put(totalLength, l);
            totalLength += l.getLength();
        }


        Population pop = sc.getPopulation();

        for (int i = 0; i < total; i++) {
            Person p = pop.getFactory().createPerson(Id.createPersonId(i));
            Plan plan = PopulationUtils.createPlan(p);
            double rH = r.nextDouble() * totalLength;
            Link l = tm.floorEntry(rH).getValue();
            Activity home = PopulationUtils.createActivityFromCoord("h", l.getCoord());
            Activity home2 = PopulationUtils.createActivityFromCoord("h", l.getCoord());
//            home.setEndTime();
            double offset = Math.max(-3600, Math.min(r.nextGaussian() * 3600, 12000));
            home.setEndTime(7 * 3600 + offset);
            double rW = r.nextDouble() * totalLength;
            Link lw = tm.floorEntry(rW).getValue();
            Activity work = PopulationUtils.createActivityFromCoord("w", lw.getCoord());

            double dist = CoordUtils.calcEuclideanDistance(lw.getCoord(), l.getCoord());
            double time = dist / 10;
//            work.setStartTime(home.getEndTime().orElse(8*3600)+time);
            work.setStartTime(home.getEndTime());
            work.setEndTime(home.getEndTime() + 8 * 3600 + time);
            work.setMaximumDuration(10 * 3600);

            plan.addActivity(home);
            plan.addLeg(PopulationUtils.createLeg("car"));
            plan.addActivity(work);
            plan.addLeg(PopulationUtils.createLeg("car"));
            plan.addActivity(home2);

//            if (r.nextDouble() < 0.5) {
//                plan.addActivity(home);
//                plan.addLeg(PopulationUtils.createLeg("car"));
//                plan.addActivity(work);
//            } else {
//
//                plan.addActivity(work);
//                plan.addLeg(PopulationUtils.createLeg("car"));
//                home.setEndTime(work.getEndTime().orElse(16*3600)+time);
//                plan.addActivity(home);
//            }
            p.addPlan(plan);
            pop.addPerson(p);

        }


    }

}
