package org.example.forestry2;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SharedChunkTicketsTest {

    @BeforeEach
    void preparerEtatStatique() {
        SharedChunkTickets.clearForTests();
    }

    @AfterEach
    void nettoyerEtatStatique() {
        SharedChunkTickets.clearForTests();
    }

    @Test
    void jePoseLeTicketAuPremierAcquireEtLeRetireALaDerniereRelease() {
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mockChunk(UUID.randomUUID(), 4, -9);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        when(chunk.removePluginChunkTicket(plugin)).thenReturn(true);

        SharedChunkTickets.acquire(plugin, chunk);
        SharedChunkTickets.acquire(plugin, chunk);

        verify(chunk, times(1)).addPluginChunkTicket(plugin);
        SharedChunkTickets.release(plugin, chunk);
        verify(chunk, never()).removePluginChunkTicket(plugin);

        SharedChunkTickets.release(plugin, chunk);
        verify(chunk, times(1)).removePluginChunkTicket(plugin);
    }

    @Test
    void jeReconnaisDeuxWrappersQuiDesignentLaMemeCleDeChunk() {
        Plugin plugin = mock(Plugin.class);
        UUID worldId = UUID.randomUUID();
        Chunk firstWrapper = mockChunk(worldId, -12, 31);
        Chunk secondWrapper = mockChunk(worldId, -12, 31);
        when(firstWrapper.addPluginChunkTicket(plugin)).thenReturn(true);
        when(firstWrapper.removePluginChunkTicket(plugin)).thenReturn(true);

        SharedChunkTickets.acquire(plugin, firstWrapper);
        SharedChunkTickets.acquire(plugin, secondWrapper);
        SharedChunkTickets.release(plugin, firstWrapper);
        SharedChunkTickets.release(plugin, secondWrapper);

        verify(firstWrapper, times(1)).addPluginChunkTicket(plugin);
        verify(secondWrapper, never()).addPluginChunkTicket(plugin);
        verify(firstWrapper, times(1)).removePluginChunkTicket(plugin);
        verify(secondWrapper, never()).removePluginChunkTicket(plugin);
    }

    @Test
    void jeNeDescendsJamaisSousZeroApresDesLiberationsSuperflues() {
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mockChunk(UUID.randomUUID(), 0, 0);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        when(chunk.removePluginChunkTicket(plugin)).thenReturn(true);

        SharedChunkTickets.release(plugin, chunk);
        SharedChunkTickets.acquire(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);

        verify(chunk, times(1)).addPluginChunkTicket(plugin);
        verify(chunk, times(1)).removePluginChunkTicket(plugin);
    }

    @Test
    void jeRestaureLEtatSiLAjoutDuTicketEchoue() {
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mockChunk(UUID.randomUUID(), 8, 2);
        IllegalStateException failure = new IllegalStateException("ajout impossible");
        when(chunk.addPluginChunkTicket(plugin)).thenThrow(failure).thenReturn(true);
        when(chunk.removePluginChunkTicket(plugin)).thenReturn(true);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> SharedChunkTickets.acquire(plugin, chunk)
        );
        assertSame(failure, thrown);

        SharedChunkTickets.acquire(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);

        verify(chunk, times(2)).addPluginChunkTicket(plugin);
        verify(chunk, times(1)).removePluginChunkTicket(plugin);
    }

    @Test
    void jePreserveUnTicketPhysiquePreexistant() {
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mockChunk(UUID.randomUUID(), 7, 14);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(false);

        SharedChunkTickets.acquire(plugin, chunk);
        SharedChunkTickets.acquire(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);

        verify(chunk, times(1)).addPluginChunkTicket(plugin);
        verify(chunk, never()).removePluginChunkTicket(plugin);

        SharedChunkTickets.acquire(plugin, chunk);
        verify(chunk, times(2)).addPluginChunkTicket(plugin);
    }

    @Test
    void jePurgeLaReservationQuandPaperNeTrouvePlusLeTicket() {
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mockChunk(UUID.randomUUID(), -3, 5);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        when(chunk.removePluginChunkTicket(plugin)).thenReturn(false);

        SharedChunkTickets.acquire(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);
        SharedChunkTickets.acquire(plugin, chunk);

        verify(chunk, times(2)).addPluginChunkTicket(plugin);
        verify(chunk, times(1)).removePluginChunkTicket(plugin);
    }

    @Test
    void jeConserveLaReservationPourReessayerApresUneExceptionDeRetrait() {
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mockChunk(UUID.randomUUID(), 22, -17);
        IllegalStateException failure = new IllegalStateException("retrait impossible");
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        when(chunk.removePluginChunkTicket(plugin)).thenThrow(failure).thenReturn(true);

        SharedChunkTickets.acquire(plugin, chunk);
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> SharedChunkTickets.release(plugin, chunk)
        );
        assertSame(failure, thrown);

        SharedChunkTickets.release(plugin, chunk);
        SharedChunkTickets.release(plugin, chunk);

        verify(chunk, times(1)).addPluginChunkTicket(plugin);
        verify(chunk, times(2)).removePluginChunkTicket(plugin);
    }

    @Test
    void jeLibereChaqueTicketUneFoisEtJisoleLesPluginsParIdentite() {
        Plugin firstPlugin = equalPluginProxy("premier");
        Plugin secondPlugin = equalPluginProxy("second");
        UUID worldId = UUID.randomUUID();
        Chunk failingChunk = mockChunk(worldId, 1, 1);
        Chunk successfulChunk = mockChunk(worldId, 2, 2);
        Chunk otherPluginChunk = mockChunk(worldId, 1, 1);
        IllegalStateException failure = new IllegalStateException("retrait differe");

        when(failingChunk.addPluginChunkTicket(same(firstPlugin))).thenReturn(true);
        when(successfulChunk.addPluginChunkTicket(same(firstPlugin))).thenReturn(true);
        when(otherPluginChunk.addPluginChunkTicket(same(secondPlugin))).thenReturn(true);
        when(failingChunk.removePluginChunkTicket(same(firstPlugin))).thenThrow(failure).thenReturn(true);
        when(successfulChunk.removePluginChunkTicket(same(firstPlugin))).thenReturn(false);
        when(otherPluginChunk.removePluginChunkTicket(same(secondPlugin))).thenReturn(true);

        SharedChunkTickets.acquire(firstPlugin, failingChunk);
        SharedChunkTickets.acquire(firstPlugin, successfulChunk);
        SharedChunkTickets.acquire(secondPlugin, otherPluginChunk);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> SharedChunkTickets.releaseAll(firstPlugin)
        );
        assertSame(failure, thrown);
        verify(failingChunk, times(1)).removePluginChunkTicket(same(firstPlugin));
        verify(successfulChunk, times(1)).removePluginChunkTicket(same(firstPlugin));
        verify(otherPluginChunk, never()).removePluginChunkTicket(same(secondPlugin));

        SharedChunkTickets.releaseAll(firstPlugin);
        verify(failingChunk, times(2)).removePluginChunkTicket(same(firstPlugin));
        verify(successfulChunk, times(1)).removePluginChunkTicket(same(firstPlugin));

        SharedChunkTickets.releaseAll(secondPlugin);
        verify(otherPluginChunk, times(1)).removePluginChunkTicket(same(secondPlugin));
    }

    private static Chunk mockChunk(UUID worldId, int x, int z) {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        when(world.getUID()).thenReturn(worldId);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        return chunk;
    }

    private static Plugin equalPluginProxy(String name) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] instanceof Plugin;
                    case "hashCode" -> 1;
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}
