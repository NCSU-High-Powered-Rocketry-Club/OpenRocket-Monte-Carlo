package com.hprc.montecarlo;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

// Assumption: You have a class MonteCarloRunRecord in this package or imported.
// If it is in a different package, uncomment and adjust the import below:
// import com.hprc.montecarlo.data.MonteCarloRunRecord;

public final class LandingDispersion6DOF {

    // Prevent instantiation
    private LandingDispersion6DOF() {}

    // Earth radius for local tangent-plane conversion (WGS84 semi-major axis roughly)
    private static final double R_EARTH_M = 6378137.0;

    // Number of polygon vertices for drawing the KML ellipse ring
    private static final int ELLIPSE_VERTS = 72;

    public static class LandingPoint {
        public int runIndex;

        // Local tangent plane offsets relative to launch site (meters)
        public double east_m;
        public double north_m;

        // WGS84 lat/lon in degrees
        public double lat_deg;
        public double lon_deg;
    }

    public static class Ellipse {
        // Semi-major axis length (m)
        public double a_m;
        // Semi-minor axis length (m)
        public double b_m;
        // Bearing of major axis, degrees clockwise from North
        public double bearing_deg;

        public Ellipse(double a_m, double b_m, double bearing_deg) {
            this.a_m = a_m;
            this.b_m = b_m;
            this.bearing_deg = bearing_deg;
        }
    }

    public static class Summary {
        public int n;

        // Launch site
        public double launchLat_deg;
        public double launchLon_deg;

        // Mean in ENU
        public double meanEast_m;
        public double meanNorth_m;

        // Mean in lat/lon
        public double meanLat_deg;
        public double meanLon_deg;

        // Covariance in ENU (m^2)
        public double cxx; // var(east)
        public double cxy; // cov(east,north)
        public double cyy; // var(north)

        public Ellipse oneSigma;
        public Ellipse twoSigma;
    }

    /**
     * Main entry point to process run records and generate export files.
     */
    public static void exportAll(
            Path outDir,
            String stem,
            List<MonteCarloRunRecord> runRecords,
            double launchLatDeg,
            double launchLonDeg
    ) throws IOException {

        // 1) Ensure output folder exists
        Files.createDirectories(outDir);

        // 2) Build landing points list
        List<LandingPoint> points = new ArrayList<>();

        // 3) Process each run record
        for (int i = 0; i < runRecords.size(); i++) {
            MonteCarloRunRecord r = runRecords.get(i);

            // 3a) Pull landing offsets
            double east = r.getLandingEastM();
            double north = r.getLandingNorthM();

            // 3b) Skip invalid points (simulations that crashed or failed to converge)
            if (Double.isNaN(east) || Double.isInfinite(east) ||
                Double.isNaN(north) || Double.isInfinite(north)) {
                continue;
            }

            // 3c) Compute lat/lon from ENU + launch site
            double[] latLon = enuToLatLonDeg(east, north, launchLatDeg, launchLonDeg);

            // 3d) Build point object
            LandingPoint p = new LandingPoint();
            p.runIndex = i;
            p.east_m = east;
            p.north_m = north;
            p.lat_deg = latLon[0];
            p.lon_deg = latLon[1];

            points.add(p);
        }

        // 4) Compute summary statistics + ellipses
        Summary summary = computeSummary(points, launchLatDeg, launchLonDeg);

        // 5) Write outputs
        writePointsCsv(outDir.resolve(stem + "_points.csv"), points, launchLatDeg, launchLonDeg);
        writeSummaryCsv(outDir.resolve(stem + "_summary.csv"), summary);
        writeKml(outDir.resolve(stem + ".kml"), points, summary);
        writePng(outDir.resolve(stem + ".png"), points, summary);
    }

    private static Summary computeSummary(List<LandingPoint> pts, double launchLatDeg, double launchLonDeg) {
        Summary s = new Summary();
        s.launchLat_deg = launchLatDeg;
        s.launchLon_deg = launchLonDeg;
        s.n = pts.size();

        // Handle degenerate cases (0 or 1 point) where covariance cannot be computed
        if (s.n < 2) {
            if (s.n == 1) {
                s.meanEast_m = pts.get(0).east_m;
                s.meanNorth_m = pts.get(0).north_m;
            } else {
                s.meanEast_m = 0.0;
                s.meanNorth_m = 0.0;
            }
            
            double[] meanLL = enuToLatLonDeg(s.meanEast_m, s.meanNorth_m, launchLatDeg, launchLonDeg);
            s.meanLat_deg = meanLL[0];
            s.meanLon_deg = meanLL[1];
            
            s.cxx = 0; s.cxy = 0; s.cyy = 0;
            s.oneSigma = new Ellipse(0, 0, 0);
            s.twoSigma = new Ellipse(0, 0, 0);
            return s;
        }

        // 1) Mean
        double sumE = 0.0;
        double sumN = 0.0;
        for (LandingPoint p : pts) {
            sumE += p.east_m;
            sumN += p.north_m;
        }
        double meanE = sumE / s.n;
        double meanN = sumN / s.n;
        
        s.meanEast_m = meanE;
        s.meanNorth_m = meanN;
        
        double[] meanLL = enuToLatLonDeg(meanE, meanN, launchLatDeg, launchLonDeg);
        s.meanLat_deg = meanLL[0];
        s.meanLon_deg = meanLL[1];

        // 2) Covariance (sample covariance, divide by n-1)
        double sumSqE = 0.0;
        double sumSqN = 0.0;
        double sumCross = 0.0;

        for (LandingPoint p : pts) {
            double de = p.east_m - meanE;
            double dn = p.north_m - meanN;
            sumSqE += de * de;
            sumSqN += dn * dn;
            sumCross += de * dn;
        }

        double cxx = sumSqE / (s.n - 1);
        double cxy = sumCross / (s.n - 1);
        double cyy = sumSqN / (s.n - 1);

        s.cxx = cxx;
        s.cxy = cxy;
        s.cyy = cyy;

        // 3) Eigen decomposition of 2x2 covariance
        // Trace and Determinant
        double tr = cxx + cyy;
        double det = cxx * cyy - cxy * cxy;

        // Calculate eigenvalues: lambda = tr/2 +/- sqrt((tr/2)^2 - det)
        double disc = (tr * tr) / 4.0 - det;
        if (disc < 0) disc = 0; // clamp for numerical noise
        double root = Math.sqrt(disc);
        
        double lambda1 = (tr / 2.0) + root; // Largest eigenvalue
        double lambda2 = (tr / 2.0) - root; // Smallest eigenvalue

        // Eigenvector direction angle from East axis (Counter-Clockwise)
        // formula: 0.5 * atan2(2*cxy, cxx - cyy)
        double thetaRad = 0.5 * Math.atan2(2 * cxy, cxx - cyy);

        // Convert to bearing from North (Clockwise)
        // if theta is angle from East CCW, bearing = 90 - theta
        double thetaDeg = Math.toDegrees(thetaRad);
        double bearingDeg = wrap360(90.0 - thetaDeg);

        // 4) 1-sigma and 2-sigma ellipse axis lengths
        // Axis length = sqrt(eigenvalue)
        double a1 = Math.sqrt(Math.max(lambda1, 0.0));
        double b1 = Math.sqrt(Math.max(lambda2, 0.0));

        s.oneSigma = new Ellipse(a1, b1, bearingDeg);
        s.twoSigma = new Ellipse(2.0 * a1, 2.0 * b1, bearingDeg);

        return s;
    }

    public static double[] enuToLatLonDeg(double east_m, double north_m, double lat0_deg, double lon0_deg) {
        double lat0 = Math.toRadians(lat0_deg);
        double lon0 = Math.toRadians(lon0_deg);

        // Flat-Earth approximation valid for short ranges (< ~50km)
        double dLat = north_m / R_EARTH_M;
        
        // Guard against division by zero at poles
        double cosLat = Math.cos(lat0);
        if (Math.abs(cosLat) < 1e-10) cosLat = 1e-10;
        
        double dLon = east_m / (R_EARTH_M * cosLat);

        double lat = lat0 + dLat;
        double lon = lon0 + dLon;

        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    public static double[] latLonToEnuM(double lat_deg, double lon_deg, double lat0_deg, double lon0_deg) {
        double lat0 = Math.toRadians(lat0_deg);

        double dLat = Math.toRadians(lat_deg - lat0_deg);
        double dLon = Math.toRadians(lon_deg - lon0_deg);

        double north = dLat * R_EARTH_M;
        double east = dLon * R_EARTH_M * Math.cos(lat0);

        return new double[]{east, north};
    }

    private static double wrap360(double deg) {
        double x = deg % 360.0;
        if (x < 0) x += 360.0;
        return x;
    }

    private static void writePointsCsv(Path file, List<LandingPoint> pts, double launchLatDeg, double launchLonDeg) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            // Write Header
            writer.write("run_index,launch_lat_deg,launch_lon_deg,landing_lat_deg,landing_lon_deg,landing_east_m,landing_north_m");
            writer.newLine();

            for (LandingPoint p : pts) {
                // Use Locale.US to ensure dot decimals
                String line = String.format(Locale.US,
                        "%d,%.8f,%.8f,%.8f,%.8f,%.4f,%.4f",
                        p.runIndex,
                        launchLatDeg, launchLonDeg,
                        p.lat_deg, p.lon_deg,
                        p.east_m, p.north_m
                );
                writer.write(line);
                writer.newLine();
            }
        }
    }

    private static void writeSummaryCsv(Path file, Summary s) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            // Write Header
            writer.write("n,launch_lat_deg,launch_lon_deg," +
                         "mean_landing_lat_deg,mean_landing_lon_deg," +
                         "mean_east_m,mean_north_m," +
                         "cxx,cxy,cyy," +
                         "one_sigma_a_m,one_sigma_b_m,one_sigma_bearing_deg," +
                         "two_sigma_a_m,two_sigma_b_m,two_sigma_bearing_deg");
            writer.newLine();

            // Write Data
            String line = String.format(Locale.US,
                    "%d,%.8f,%.8f,%.8f,%.8f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                    s.n, s.launchLat_deg, s.launchLon_deg,
                    s.meanLat_deg, s.meanLon_deg,
                    s.meanEast_m, s.meanNorth_m,
                    s.cxx, s.cxy, s.cyy,
                    s.oneSigma.a_m, s.oneSigma.b_m, s.oneSigma.bearing_deg,
                    s.twoSigma.a_m, s.twoSigma.b_m, s.twoSigma.bearing_deg
            );
            writer.write(line);
            writer.newLine();
        }
    }

    private static void writeKml(Path file, List<LandingPoint> pts, Summary s) throws IOException {
        String kmlText = buildKmlString(pts, s);
        Files.write(file, kmlText.getBytes(StandardCharsets.UTF_8));
    }

    private static String buildKmlString(List<LandingPoint> pts, Summary s) {
        StringBuilder sb = new StringBuilder();

        // KML Header
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
        sb.append("<Document>\n");
        sb.append("  <name>Landing Dispersion</name>\n");

        // --- Define Styles ---
        
        // Launch Site (Green Paddle)
        sb.append("  <Style id=\"launchStyle\">\n")
          .append("    <IconStyle><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/grn-stars.png</href></Icon></IconStyle>\n")
          .append("  </Style>\n");

        // Mean Landing Point (Red Paddle)
        sb.append("  <Style id=\"meanStyle\">\n")
          .append("    <IconStyle><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-stars.png</href></Icon></IconStyle>\n")
          .append("  </Style>\n");

        // Individual Landing Point (Small Circle)
        sb.append("  <Style id=\"pointStyle\">\n")
          .append("    <IconStyle><scale>0.5</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle>\n")
          .append("  </Style>\n");

        // 1-Sigma Ellipse (Yellow, Semi-transparent fill)
        sb.append("  <Style id=\"oneSigmaStyle\">\n")
          .append("    <LineStyle><color>ff00ffff</color><width>2</width></LineStyle>\n")
          .append("    <PolyStyle><color>4000ffff</color></PolyStyle>\n") // KML color is AABBGGRR
          .append("  </Style>\n");

        // 2-Sigma Ellipse (Red, Semi-transparent fill)
        sb.append("  <Style id=\"twoSigmaStyle\">\n")
          .append("    <LineStyle><color>ff0000ff</color><width>2</width></LineStyle>\n")
          .append("    <PolyStyle><color>200000ff</color></PolyStyle>\n")
          .append("  </Style>\n");

        // --- Placemarks ---

        // Launch Site
        sb.append(String.format(Locale.US, 
            "  <Placemark>\n    <name>Launch Site</name>\n    <styleUrl>#launchStyle</styleUrl>\n    <Point><coordinates>%.8f,%.8f,0</coordinates></Point>\n  </Placemark>\n", 
            s.launchLon_deg, s.launchLat_deg));

        // Mean Landing
        sb.append(String.format(Locale.US, 
            "  <Placemark>\n    <name>Mean Landing</name>\n    <styleUrl>#meanStyle</styleUrl>\n    <Point><coordinates>%.8f,%.8f,0</coordinates></Point>\n  </Placemark>\n", 
            s.meanLon_deg, s.meanLat_deg));

        // Draw Ellipses only if we have enough points
        if (s.n >= 2) {
            // 1-Sigma Polygon
            sb.append("  <Placemark>\n    <name>1-Sigma Dispersion</name>\n    <styleUrl>#oneSigmaStyle</styleUrl>\n    <Polygon>\n      <outerBoundaryIs>\n        <LinearRing>\n          <coordinates>\n");
            List<String> oneSigmaCoords = buildEllipseRingLonLat(s, s.oneSigma);
            for (String coord : oneSigmaCoords) {
                sb.append("            ").append(coord).append("\n");
            }
            sb.append("          </coordinates>\n        </LinearRing>\n      </outerBoundaryIs>\n    </Polygon>\n  </Placemark>\n");

            // 2-Sigma Polygon
            sb.append("  <Placemark>\n    <name>2-Sigma Dispersion</name>\n    <styleUrl>#twoSigmaStyle</styleUrl>\n    <Polygon>\n      <outerBoundaryIs>\n        <LinearRing>\n          <coordinates>\n");
            List<String> twoSigmaCoords = buildEllipseRingLonLat(s, s.twoSigma);
            for (String coord : twoSigmaCoords) {
                sb.append("            ").append(coord).append("\n");
            }
            sb.append("          </coordinates>\n        </LinearRing>\n      </outerBoundaryIs>\n    </Polygon>\n  </Placemark>\n");
        }

        // Folder for individual points
        sb.append("  <Folder>\n    <name>Landing Points</name>\n");
        for (LandingPoint p : pts) {
            sb.append(String.format(Locale.US, 
                "    <Placemark>\n      <styleUrl>#pointStyle</styleUrl>\n      <Point><coordinates>%.8f,%.8f,0</coordinates></Point>\n    </Placemark>\n", 
                p.lon_deg, p.lat_deg));
        }
        sb.append("  </Folder>\n");

        sb.append("</Document>\n</kml>");
        return sb.toString();
    }

    private static List<String> buildEllipseRingLonLat(Summary s, Ellipse e) {
        List<String> coords = new ArrayList<>();
        int N = ELLIPSE_VERTS;

        // Convert bearing (Clockwise from North) to math angle (Counter-Clockwise from East)
        double thetaFromEastDeg = 90.0 - e.bearing_deg;
        double thetaRot = Math.toRadians(thetaFromEastDeg);
        double cosT = Math.cos(thetaRot);
        double sinT = Math.sin(thetaRot);

        for (int i = 0; i <= N; i++) {
            // Parameter t from 0 to 2PI
            double t = 2.0 * Math.PI * i / (double) N;

            // 1) Ellipse geometry in its own principal axis frame
            double x = e.a_m * Math.cos(t);
            double y = e.b_m * Math.sin(t);

            // 2) Rotate to Align with East/North
            double eastRot = x * cosT - y * sinT;
            double northRot = x * sinT + y * cosT;

            // 3) Translate to Mean Center (World ENU)
            double eastWorld = s.meanEast_m + eastRot;
            double northWorld = s.meanNorth_m + northRot;

            // 4) Convert ENU to Lat/Lon
            double[] latLon = enuToLatLonDeg(eastWorld, northWorld, s.launchLat_deg, s.launchLon_deg);

            // 5) Add to KML string list (Format: lon,lat,alt)
            coords.add(String.format(Locale.US, "%.8f,%.8f,0", latLon[1], latLon[0]));
        }

        return coords;
    }

    private static void writePng(Path file, List<LandingPoint> pts, Summary s) throws IOException {
        final int size = 1024;
        final int pad = 80;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);

        // compute bounds in ENU (include ellipses)
        double minE = 0, maxE = 0, minN = 0, maxN = 0;
        boolean hasPts = !pts.isEmpty();

        if (hasPts) {
            minE = maxE = pts.get(0).east_m;
            minN = maxN = pts.get(0).north_m;
            for (LandingPoint p : pts) {
                minE = Math.min(minE, p.east_m);
                maxE = Math.max(maxE, p.east_m);
                minN = Math.min(minN, p.north_m);
                maxN = Math.max(maxN, p.north_m);
            }
        }

        if (s != null && s.oneSigma != null && s.twoSigma != null) {
            double maxA = Math.max(s.oneSigma.a_m, s.twoSigma.a_m);
            double maxB = Math.max(s.oneSigma.b_m, s.twoSigma.b_m);
            minE = Math.min(minE, s.meanEast_m - maxA);
            maxE = Math.max(maxE, s.meanEast_m + maxA);
            minN = Math.min(minN, s.meanNorth_m - maxB);
            maxN = Math.max(maxN, s.meanNorth_m + maxB);
        }

        if (!hasPts) {
            minE = -1; maxE = 1; minN = -1; maxN = 1;
        }

        double spanE = Math.max(1e-6, maxE - minE);
        double spanN = Math.max(1e-6, maxN - minN);
        double scale = Math.min((size - 2.0 * pad) / spanE, (size - 2.0 * pad) / spanN);

        final double minEFinal = minE;
        final double minNFinal = minN;

        // helper to map ENU -> image pixels
        java.util.function.BiFunction<Double, Double, Point> map = (e, n) -> {
            int x = (int) Math.round(pad + (e - minEFinal) * scale);
            int y = (int) Math.round(size - pad - (n - minNFinal) * scale);
            return new Point(x, y);
        };

        // draw ellipses (2-sigma then 1-sigma)
        if (s != null && s.n >= 2) {
            drawEllipse(g, s, s.twoSigma, map, new Color(255, 0, 0, 50), new Color(255, 0, 0, 180));
            drawEllipse(g, s, s.oneSigma, map, new Color(255, 255, 0, 80), new Color(255, 255, 0, 200));
        }

        // draw points
        g.setColor(new Color(0, 0, 0, 140));
        int r = 3;
        for (LandingPoint p : pts) {
            Point pt = map.apply(p.east_m, p.north_m);
            g.fillOval(pt.x - r, pt.y - r, r * 2, r * 2);
        }

        // launch site at (0,0)
        Point launch = map.apply(0.0, 0.0);
        g.setColor(new Color(0, 160, 0));
        g.fillOval(launch.x - 6, launch.y - 6, 12, 12);

        // mean landing point
        if (s != null) {
            Point mean = map.apply(s.meanEast_m, s.meanNorth_m);
            g.setColor(new Color(200, 0, 0));
            g.fillOval(mean.x - 6, mean.y - 6, 12, 12);
        }

        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    private static void drawEllipse(
            Graphics2D g,
            Summary s,
            Ellipse e,
            java.util.function.BiFunction<Double, Double, Point> map,
            Color fill,
            Color stroke
    ) {
        if (e == null) return;

        int N = ELLIPSE_VERTS;
        int[] xs = new int[N + 1];
        int[] ys = new int[N + 1];

        double thetaFromEastDeg = 90.0 - e.bearing_deg;
        double thetaRot = Math.toRadians(thetaFromEastDeg);
        double cosT = Math.cos(thetaRot);
        double sinT = Math.sin(thetaRot);

        for (int i = 0; i <= N; i++) {
            double t = 2.0 * Math.PI * i / (double) N;
            double x = e.a_m * Math.cos(t);
            double y = e.b_m * Math.sin(t);

            double eastRot = x * cosT - y * sinT;
            double northRot = x * sinT + y * cosT;

            double eastWorld = s.meanEast_m + eastRot;
            double northWorld = s.meanNorth_m + northRot;

            Point pt = map.apply(eastWorld, northWorld);
            xs[i] = pt.x;
            ys[i] = pt.y;
        }

        g.setColor(fill);
        g.fillPolygon(xs, ys, xs.length);
        g.setColor(stroke);
        g.setStroke(new BasicStroke(2f));
        g.drawPolygon(xs, ys, xs.length);
    }
}