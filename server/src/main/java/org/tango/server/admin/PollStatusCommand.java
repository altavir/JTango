/**
 * Copyright (C) :     2012
 * <p>
 * Synchrotron Soleil
 * L'Orme des merisiers
 * Saint Aubin
 * BP48
 * 91192 GIF-SUR-YVETTE CEDEX
 * <p>
 * This file is part of Tango.
 * <p>
 * Tango is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * Tango is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public License
 * along with Tango.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tango.server.admin;

import fr.esrf.Tango.DevFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tango.server.ExceptionMessages;
import org.tango.server.IPollable;
import org.tango.server.attribute.AttributeImpl;
import org.tango.server.build.DeviceClassBuilder;
import org.tango.server.command.CommandImpl;
import org.tango.server.servant.DeviceImpl;
import org.tango.utils.DevFailedUtils;
import org.tango.utils.TangoUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * @author Igor Khokhriakov <igor.khokhriakov@hzg.de>
 * @since 8/2/17
 */
public class PollStatusCommand implements Callable<String[]> {
    private final Logger logger = LoggerFactory.getLogger(PollStatusCommand.class);

    private final String deviceName;
    private final List<DeviceClassBuilder> classList;

    PollStatusCommand(String deviceName, List<DeviceClassBuilder> classList) {
        this.deviceName = deviceName;
        this.classList = classList;
    }

    /**
     * @return an array of strings with polling info
     * @throws DevFailed in case device cannot be found
     */
    @Override
    public String[] call() throws DevFailed {
        final List<String> result = new ArrayList<String>();
        final DeviceImpl device = tryFindDeviceByName(deviceName);
        addPolledStatus(result, device, device.getCommandList());
        addPolledStatus(result, device, device.getAttributeList());
        return result.toArray(new String[0]);
    }

    private void addPolledStatus(List<String> pollStatus, final DeviceImpl device, List<? extends IPollable> pollableList) {
        List<String> polledCommands = pollableList.stream().filter(IPollable::isPolled)
                .map(pollable -> buildPollingStatus(device, pollable).toString()).collect(Collectors.toList());
        pollStatus.addAll(polledCommands);
    }

    private DeviceImpl tryFindDeviceByName(final String deviceName) throws DevFailed {
        List<DeviceImpl> allDevices = classList.stream()
                .flatMap((DeviceClassBuilder deviceClassBuilder) -> deviceClassBuilder.getDeviceImplList().stream())
                .collect(Collectors.toList());

        Optional<DeviceImpl> device = allDevices.stream().filter(input -> deviceName.equalsIgnoreCase(input.getName())).findFirst();
        //Iterables.tryFind(allDevices, input -> deviceName.equalsIgnoreCase(input.getName()));

        if (!device.isPresent()) {
            //try to find device by alias
            device = allDevices.stream().filter(input -> {
                try {
                    //returns alias or deviceName
                    return TangoUtil.getfullNameForDevice(deviceName).equalsIgnoreCase(input.getName());
                } catch (DevFailed devFailed) {
                    logger.warn("Failed to get full name for device {}", deviceName);
                    DevFailedUtils.logDevFailed(devFailed, logger);
                    return false;
                }
            }).findFirst();
        }
        if (!device.isPresent()) {
            throw DevFailedUtils.newDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, deviceName + AdminDevice.DOES_NOT_EXIST);
        }
        return device.get();
    }

    private StringBuilder buildPollingStatus(final DeviceImpl device, final IPollable pollable) {
        // XXX WARN!!! The string table is parsed by jive.Do not change a letter
        // of
        // the result!
        //TODO do we need special output formatter for jive?
        final StringBuilder buf;
        if (pollable instanceof AttributeImpl) {
            buf = new StringBuilder("Polled attribute name = ");
        } else {
            buf = new StringBuilder("Polled command name = ");
        }

        buf.append(pollable.getName());
        if (pollable.getPollingPeriod() == 0) {
            buf.append("\nPolling externally triggered");
        } else {
            buf.append("\nPolling period (mS) = ");
            buf.append(pollable.getPollingPeriod());
        }
        buf.append("\nPolling ring buffer depth = ");
        buf.append(pollable.getPollRingDepth());
        if (pollable instanceof AttributeImpl && device.getAttributeHistorySize((AttributeImpl) pollable) == 0) {
            buf.append("\nNo data recorded yet");
        }
        if (pollable instanceof CommandImpl && device.getCommandHistorySize((CommandImpl) pollable) == 0) {
            buf.append("\nNo data recorded yet");
        }

        if (!pollable.getLastDevFailed().isEmpty()) {
            buf.append("\nLast attribute read FAILED :\n").append(pollable.getLastDevFailed());
        } else {
            buf.append("\nTime needed for the last attribute reading (mS) = ");
            buf.append(pollable.getExecutionDuration());
            buf.append("\nData not updated since ");
            buf.append(System.currentTimeMillis() - (long) pollable.getLastUpdateTime());
            buf.append(" mS\nDelta between last records (in mS) = ");
            buf.append(pollable.getDeltaTime());
        }
        return buf;
    }
}
