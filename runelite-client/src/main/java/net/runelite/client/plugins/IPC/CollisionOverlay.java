package net.runelite.client.plugins.ipcinput;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class CollisionOverlay extends Overlay
{
    private final Client client;

    // tune if you want a bigger/smaller window
    private static final int RADIUS = 15;
    private static final Color BLOCKED_FILL = new Color(255, 0, 0, 70); // translucent red

    CollisionOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        // no priority needed; default is fine
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (client.getLocalPlayer() == null) return null;
        int plane = client.getPlane();
        var cms = client.getCollisionMaps();
        if (cms == null || plane < 0 || plane >= cms.length || cms[plane] == null) return null;

        int[][] flags = cms[plane].getFlags();
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        WorldPoint me = client.getLocalPlayer().getWorldLocation();
        int wx = me.getX(), wy = me.getY();

        for (int gy = wy + RADIUS; gy >= wy - RADIUS; gy--)
        {
            for (int gx = wx - RADIUS; gx <= wx + RADIUS; gx++)
            {
                int lx = gx - baseX;
                int ly = gy - baseY;
                if (lx < 0 || ly < 0 || lx >= 104 || ly >= 104) continue;

                int f = flags[lx][ly];
                boolean solid  = (f & 0x200000) != 0; // SOLID
                boolean object = (f & 0x000100) != 0; // OBJECT
                if (!(solid || object)) continue; // only paint blocked

                LocalPoint lp = LocalPoint.fromWorld(client, gx, gy);
                if (lp == null) continue;

                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly != null)
                {
                    g.setColor(BLOCKED_FILL);
                    g.fill(poly);
                }
            }
        }
        return null;
    }
}
