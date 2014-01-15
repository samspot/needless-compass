/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.namal.needless.compass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author nmalik
 */
public class NeedlessCompassApp {
    private Sites sites;
    private Houses houses;
    private Trips trips;
    private Map<String, Set<Site>> categorySiteMap;

    public NeedlessCompassApp() {
    }

    public void initialize() throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("sites.json");
                InputStreamReader isr = new InputStreamReader(is, Charset.defaultCharset())) {
            Gson g = new Gson();
            sites = g.fromJson(isr, Sites.class);
            if (null == sites || sites.getSites().length <= 0) {
                throw new IllegalStateException("No sites found!");
            }
        }
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("houses.json");
                InputStreamReader isr = new InputStreamReader(is, Charset.defaultCharset())) {
            Gson g = new Gson();
            houses = g.fromJson(isr, Houses.class);
            if (null == houses || houses.getHouses().length <= 0) {
                throw new IllegalStateException("No houses found!");
            }
        }
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("trips.json");
                InputStreamReader isr = new InputStreamReader(is, Charset.defaultCharset())) {
            Gson g = new Gson();
            trips = g.fromJson(isr, Trips.class);
            if (null == trips || trips.getTrips().length <= 0) {
                throw new IllegalStateException("No trips found!");
            }
        }

        categorySiteMap = new HashMap<>();

        for (Site site : sites.getSites()) {
            for (String category : site.getCategories()) {
                if (!categorySiteMap.containsKey(category)) {
                    categorySiteMap.put(category, new HashSet<Site>());
                }
                categorySiteMap.get(category).add(site);
            }
        }
    }

    public String process() {
        // for each house + trip find all categories that satisfy the waypoint needs and then compute final best (lowest) score
        for (House house : houses.getHouses()) {
            for (Trip trip : trips.getTrips()) {
                // reset current site to house and create starting waypoint (as 'previous')
                Waypoint currentWaypoint = new Waypoint(house);

                house.addPath(trip, currentWaypoint);

                addWaypoints(house, currentWaypoint, trip.getCategoryNames(), 0);
            }
            // got a bunch 'o data, now figure out a score for this house!
            // get the lowest score from each of the first children of the path's waypoint

            Map<Trip, Waypoint> paths = house.getPaths();
            double score = 0;
            for (Trip trip : paths.keySet()) {
                Waypoint root = paths.get(trip);
                double s = computeMinScore(root) * trip.getFrequency().doubleValue();
                score += s;
                // System.out.println(house.getSite().getName() + " | " + trip.getName() + " | " + s);
            }
            house.setScore(new Score(score));
        }

        Gson g = new GsonBuilder().setPrettyPrinting().create();
        return g.toJson(houses);
    }

    /**
     * Recursively add next waypoints to current waypoint starting at given
     * cateogry name index and ending with the given house.
     *
     * @param current
     * @param categoryNames
     * @param categoryNameIndex
     */
    public void addWaypoints(House house, Waypoint current, String[] categoryNames, int categoryNameIndex) {
        if (categoryNameIndex >= categoryNames.length) {
            Waypoint childWaypoint = new Waypoint(house, current);
            return;
        }

        if (!categorySiteMap.containsKey(categoryNames[categoryNameIndex])) {
            throw new IllegalStateException("Unable to find category data: " + categoryNames[categoryNameIndex]);
        }

        for (Site site : categorySiteMap.get(categoryNames[categoryNameIndex])) {
            Waypoint childWaypoint = new Waypoint(site, current);
            addWaypoints(house, childWaypoint, categoryNames, categoryNameIndex + 1);
        }
    }

    public double computeMinScore(Waypoint wp) {
        Iterator<Waypoint> children = wp.getNext();
        double minScore = 0.0;
        while (children.hasNext()) {
            double s = computeMinScore(children.next());
            if (0 == minScore) {
                minScore = s;
            } else {
                minScore = Math.min(minScore, s);
            }
        }

        return minScore + wp.getScore().doubleValue();
    }

    public static void main(String[] args) {
        NeedlessCompassApp app = new NeedlessCompassApp();
        try {
            app.initialize();
            System.out.println(app.process());
        } catch (IOException e) {
            System.err.println("Failed to start application:");
            e.printStackTrace();;
        }
    }
}
