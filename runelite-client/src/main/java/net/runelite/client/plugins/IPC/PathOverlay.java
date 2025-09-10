package net.runelite.client.plugins.ipcinput;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class PathOverlay extends Overlay
{
    private final Client client;

    // volatile for cross-thread set from clientThread.invokeLater
    private volatile List<WorldPoint> waypoints = new ArrayList<>();
    private volatile @Nullable WorldPoint start;
    private volatile @Nullable WorldPoint goal;

    public PathOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_MED);
    }

    public void clearPath()
    {
        this.waypoints = new ArrayList<>();
        this.start = null;
        this.goal = null;
    }

    public void setPath(List<WorldPoint> wps, @Nullable WorldPoint start, @Nullable WorldPoint goal)
    {
        // copy into new list to avoid concurrent modification
        this.waypoints = (wps == null) ? new ArrayList<>() : new ArrayList<>(wps);
        this.start = start;
        this.goal = goal;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (waypoints == null || waypoints.isEmpty())
            return null;

        final int plane = client.getPlane();
        final Color lineColor = new Color(0, 200, 255, 180);
        final Color dotColor  = new Color(0, 255, 120, 220);
        final Color startCol  = new Color(255, 230, 0, 220);
        final Color goalCol   = new Color(255, 80, 80, 220);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setStroke(new BasicStroke(2.0f));

        // Draw segments
        int lastX = -1, lastY = -1;
        g2.setColor(lineColor);
        for (WorldPoint wp : waypoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client, wp.getX(), wp.getY());
            if (lp == null) continue;
            net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
            if (p == null) continue;

            int x = p.getX();
            int y = p.getY();

            if (lastX != -1)
            {
                g2.drawLine(lastX, lastY, x, y);
            }
            lastX = x; lastY = y;
        }

        // Draw dots
        g2.setColor(dotColor);
        for (WorldPoint wp : waypoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client, wp.getX(), wp.getY());
            if (lp == null) continue;
            net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
            if (p == null) continue;
            int x = p.getX(), y = p.getY();
            g2.fillOval(x - 3, y - 3, 6, 6);
        }

        // Mark start / goal
        if (start != null)
        {
            LocalPoint lp = LocalPoint.fromWorld(client, start.getX(), start.getY());
            if (lp != null)
            {
                net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
                if (p != null)
                {
                    g2.setColor(startCol);
                    g2.fillOval(p.getX() - 5, p.getY() - 5, 10, 10);
                }
            }
        }
        if (goal != null)
        {
            LocalPoint lp = LocalPoint.fromWorld(client, goal.getX(), goal.getY());
            if (lp != null)
            {
                net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
                if (p != null)
                {
                    g2.setColor(goalCol);
                    g2.fillRect(p.getX() - 5, p.getY() - 5, 10, 10);
                }
            }
        }

        g2.dispose();
        return null;
    }

    // Small shim to make Overlay compile on both old/new versions
    private static final class Dimension extends java.awt.Dimension {
        Dimension() { super(0, 0); }
    }
}
