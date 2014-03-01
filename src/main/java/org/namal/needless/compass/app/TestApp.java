/*
 * Copyright (C) 2014 Naveen Malik
 *
 * Needless Compass is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Needless Compass is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Needless Compass.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.namal.needless.compass.app;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import org.namal.mongo.MongoCRUD;
import org.namal.mongo.model.geo.Shape;
import org.namal.needless.compass.model.House;
import org.namal.needless.compass.model.Score;
import org.namal.needless.compass.model.google.Geocode;
import org.namal.needless.compass.model.google.Result;
import org.namal.needless.compass.model.PointOfInterest;
import org.namal.needless.compass.model.RouteTree;
import org.namal.needless.compass.model.Trip;

/**
 *
 * @author nmalik
 */
public class TestApp {
    private static final int FIND_POI_LIMIT = 3;
    private static final String COLLECTION_POI = "poi";
    private static final String COLLECTION_ROUTE = "route";
    private static final String COLLECTION_TRIP = "trip";

    private final MongoCRUD crud = new MongoCRUD("needlesscompass");

    public TestApp() {
    }

    public void initialize() throws IOException {
        crud.createIndex2dsphere(COLLECTION_POI, Shape.ATTRIBUTE_LOCATION);
        crud.createIndex(COLLECTION_POI, PointOfInterest.ATTRIBUTE_CATEGORIES);
    }

    public String process(String owner) {
        // get all trips
        Iterator<Trip> tripItr = crud.find(
                // collection
                COLLECTION_TRIP,
                // query
                String.format("{owner:%s}", owner),
                // projection
                null
        );

        List<Trip> trips = new ArrayList<>();
        while (tripItr.hasNext()) {
            trips.add(tripItr.next());
        }

        // get all houses and initialize a sorted set.  scores will sort it eventually
        TreeSet<House> sortedHouses = new TreeSet<>();

        Iterator<House> houseItr = crud.find(
                // collection
                COLLECTION_POI,
                // query
                String.format("{owner:%s,categories:%s}", owner, House.CATEGORY_HOUSE),
                // projection
                null
        );

        while (houseItr.hasNext()) {
            House house = houseItr.next();
            // process each house
            process(owner, house, trips);
            // and add it to the sorted set
            sortedHouses.add(house);
        }

        // return json with the best scored house first
        return new Gson().toJson(sortedHouses);
    }

    public void process(String owner, House house, List<Trip> trips) {

        // for each trip find closes sites that match using global limit
        for (Trip trip : trips) {
            RouteTree tree = process(owner, house, trip);
        }
        // got a bunch 'o data, now figure out a score for this house!
        // get the lowest score from each of the first children of the path's waypoint

        Map<Trip, Waypoint> paths = house.getPaths();
        double score = 0;
        for (Trip trip : paths.keySet()) {
            Waypoint root = paths.get(trip);
            score += (computeMinScore(root) * trip.getFrequency().doubleValue());
        }
        house.setScore(new Score(score));
    }

    public double computeMinScore(RouteTree root) {
        // collect sorted set of leaf waypoints. first will be smallest score
        Set<Waypoint> leafs = new TreeSet<>();

        Stack<Iterator<Waypoint>> stack = new Stack<>();
        stack.push(root.iterator());

        while (!stack.empty() && stack.peek().hasNext()) {
            Waypoint child = stack.peek().next();
            child.getScore(); // for now just to prime the score
            if (child.getNext().isEmpty()) {
                leafs.add(child);
                stack.pop();
            } else {
                stack.push(child.iterator());
            }
        }

        return leafs.isEmpty() ? 0.0 : leafs.iterator().next().getScore().doubleValue();
    }

    private RouteTree process(String owner, House house, Trip trip) {
        // for each trip prep a route and collect transient points
        RouteTree root = new RouteTree(house);
        List<RouteTree> processing = new ArrayList<>();

        // initialize processing list with root
        processing.add(root);

        for (String categoryName : trip.getCategoryNames()) {
            // add children to each node we need to process
            // for each tree processed collect its children as the next round to process
            List<RouteTree> newProcessing = new ArrayList<>();
            for (RouteTree tree : processing) {
                addChildren(owner, tree, categoryName);
                newProcessing.addAll(tree.children);
            }

            // simply replace the processing list to kick off the next round of processing
            processing = newProcessing;
        }

        return root;
    }

    /**
     * Add children to the parent by finding closest POI within limit.
     *
     * @param owner
     * @param parent
     * @param categoryName
     */
    private void addChildren(String owner, RouteTree parent, String categoryName) {
        // get POI near last coordinates
        Iterator<PointOfInterest> poiItr = crud.find(
                // collection
                COLLECTION_POI,
                // query
                String.format("{%s: { $near: { $geometry: { type: \"Point\", coordinates: [%f,%f] } } }, owner:%s, category:%s }",
                        Shape.ATTRIBUTE_LOCATION, // location attribute
                        parent.root.getLocation().getCoordinates()[0][0], // lat
                        parent.root.getLocation().getCoordinates()[0][1], // long
                        owner,
                        categoryName
                ),
                // projection
                null,
                // limit
                FIND_POI_LIMIT
        );

        while (poiItr.hasNext()) {
            parent.children.add(new RouteTree(poiItr.next()));
        }
    }

    /**
     * Recursively add next waypoints to current waypoint starting at given
     * cateogry name index and ending with the given house.
     *
     * @param start
     * @param current
     * @param categoryNames
     * @param categoryNameIndex
     */
    public void addWaypoints(Coordinate start, Waypoint current, String[] categoryNames, int categoryNameIndex) {
        if (categoryNameIndex >= categoryNames.length) {
            Waypoint childWaypoint = new Waypoint(start, current);
            return;
        }

        if (!categorySiteMap.containsKey(categoryNames[categoryNameIndex])) {
            throw new IllegalStateException("Unable to find category data: " + categoryNames[categoryNameIndex]);
        }

        for (Site site : categorySiteMap.get(categoryNames[categoryNameIndex])) {
            Waypoint childWaypoint = new Waypoint(site, current);
            addWaypoints(start, childWaypoint, categoryNames, categoryNameIndex + 1);
        }
    }

    public double computeMinScore(Waypoint wp) {
        // collect sorted set of leaf waypoints. first will be smallest score
        Set<Waypoint> leafs = new TreeSet<>();

        Stack<Iterator<Waypoint>> stack = new Stack<>();
        stack.push(wp.iterator());

        while (!stack.empty() && stack.peek().hasNext()) {
            Waypoint child = stack.peek().next();
            child.getScore(); // for now just to prime the score
            if (child.getNext().isEmpty()) {
                leafs.add(child);
                stack.pop();
            } else {
                stack.push(child.iterator());
            }
        }

        return leafs.isEmpty() ? 0.0 : leafs.iterator().next().getScore().doubleValue();
    }

    public static void enrichSite(Site site) throws MalformedURLException, IOException {
        String geocodeApiUrlString;
        if (site.getAddress() != null && !site.getAddress().isEmpty()) {
            geocodeApiUrlString = "http://maps.googleapis.com/maps/api/geocode/json?sensor=false&address=" + URLEncoder.encode(site.getAddress(), "UTF-8");
        } else if (site.getLatitude() != null && site.getLongitude() != null) {
            geocodeApiUrlString = "http://maps.googleapis.com/maps/api/geocode/json?sensor=false&latlng=" + site.getLatitude().toString() + "," + site.getLongitude().toString();
        } else {
            throw new MalformedURLException("Unable to construct URL, must supply either address or latitude/longitude on Site");
        }
        URL url = new URL(geocodeApiUrlString);
        URLConnection con = url.openConnection();

        StringBuilder buff = new StringBuilder();

        try (InputStream is = con.getInputStream();
                InputStreamReader isr = new InputStreamReader(is, Charset.defaultCharset());
                BufferedReader reader = new BufferedReader(isr)) {
            String line;
            while ((line = reader.readLine()) != null) {
                buff.append(line).append("\n");
            }
        }

        String jsonString = buff.toString();
        Gson g = new Gson();
        Geocode geocode = g.fromJson(jsonString, Geocode.class);

        if (!"OK".equals(geocode.getStatus())) {
            throw new RuntimeException("Status from geocode API not OK: " + jsonString);
        }

        // use only first result
        Result result = geocode.getResults()[0];
        site.setAddress(result.getFormatted_address());
        site.setLatitude(new BigDecimal(result.getGeometry().getLocation().getLat()));
        site.setLongitude(new BigDecimal(result.getGeometry().getLocation().getLng()));
        if (site.getName() == null || site.getName().isEmpty()) {
            site.setName(result.getFormatted_address());
        }
    }

    public static void main(String[] args) {
        TestApp app = new TestApp();
        try {
//            app.initialize();
//            System.out.println(app.process());
            House house = new House();
            house.setAddress("Raleigh NC");
            enrichSite(house);
            System.out.println(new Gson().toJson(house));
        } catch (IOException e) {
            System.err.println("Failed to start application:");
            e.printStackTrace();
        }
    }
}
