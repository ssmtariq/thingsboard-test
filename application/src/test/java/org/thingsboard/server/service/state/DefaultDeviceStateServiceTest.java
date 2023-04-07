/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.state;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

@RunWith(MockitoJUnitRunner.class)
public class DefaultDeviceStateServiceTest {

    @Mock
    TenantService tenantService;
    @Mock
    DeviceService deviceService;
    @Mock
    AttributesService attributesService;
    @Mock
    TimeseriesService tsService;
    @Mock
    TbClusterService clusterService;
    @Mock
    PartitionService partitionService;
    @Mock
    DeviceStateData deviceStateDataMock;
    @Mock
    TbServiceInfoProvider serviceInfoProvider;

    DeviceId deviceId = DeviceId.fromString("00797a3b-7aeb-4b5b-b57a-c2a810d0f112");

    DefaultDeviceStateService service;

    @Before
    public void setUp() {
        service = spy(new DefaultDeviceStateService(tenantService, deviceService, attributesService, tsService, clusterService, partitionService, serviceInfoProvider, null, null));
    }

    @Test
    public void givenDeviceIdFromDeviceStatesMap_whenGetOrFetchDeviceStateData_thenNoStackOverflow() {
        service.deviceStates.put(deviceId, deviceStateDataMock);
        DeviceStateData deviceStateData = service.getOrFetchDeviceStateData(deviceId);
        assertThat(deviceStateData, is(deviceStateDataMock));
        Mockito.verify(service, never()).fetchDeviceStateDataUsingEntityDataQuery(deviceId);
    }

    @Test
    public void givenDeviceIdWithoutDeviceStateInMap_whenGetOrFetchDeviceStateData_thenFetchDeviceStateData() {
        service.deviceStates.clear();
        willReturn(deviceStateDataMock).given(service).fetchDeviceStateDataUsingEntityDataQuery(deviceId);
        DeviceStateData deviceStateData = service.getOrFetchDeviceStateData(deviceId);
        assertThat(deviceStateData, is(deviceStateDataMock));
        Mockito.verify(service, times(1)).fetchDeviceStateDataUsingEntityDataQuery(deviceId);
    }

}